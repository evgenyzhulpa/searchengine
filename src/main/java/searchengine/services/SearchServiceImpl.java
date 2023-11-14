package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.dto.search.SearchData;
import searchengine.dto.search.SearchRequest;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.search.SnippetData;
import searchengine.model.*;
import searchengine.parsers.HtmlParser;
import searchengine.parsers.LemmaFinder;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {
    @Autowired
    private final SiteRepository siteRepository;
    @Autowired
    private final PageRepository pageRepository;
    @Autowired
    private final LemmaRepository lemmaRepository;
    private final SitesList sitesList;
    private LemmaFinder lemmaFinder;
    private final Logger logger = LogManager.getLogger("indexingServiceLogger");
    private static final int FREQUENCY_OCCURRENCE_MAX_PERCENT = 90;
    private static final int MAX_SEARCH_RESULT_LENGTH = 200;
    private int maxLengthOfSnippetPhrase = 0;

    {
        try {
            lemmaFinder = LemmaFinder.getInstance();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SearchResponse search(SearchRequest request)  {
        SearchResponse response = getSearchResponseBySearchRequestCorrectness(request);
        if (!response.isResult()) {
            return response;
        }
        try {
            Set<String> lemmas = getLemmasFromWords(request.getQuery());
            List<Site> sites = getSitesForSearch(request.getSiteUrl());
            List<Lemma> lemmasEntities = getLemmasEntities(lemmas, sites);
            if (lemmasEntities.isEmpty()) {
                response.setCount(0);
                response.setData(getSearchDataArray(new ArrayList<>(), lemmas, request));
                return response;
            }
            lemmas = getLemmasFromLemmaEntities(lemmasEntities);
            List<Page> matchingPages = getMatchingPages(lemmasEntities, lemmas.size(), sites);
            response.setCount(matchingPages.size());
            response.setData(getSearchDataArray(matchingPages, lemmas, request));
        } catch (IOException exception) {
            logger.error(exception.getMessage());
        }
        return response;
    }

    private SearchResponse getSearchResponseBySearchRequestCorrectness(SearchRequest request) {
        SearchResponse response = new SearchResponse();
        String errorMessage = getMessageAboutIncorrectSearchData(request);
        if (!errorMessage.isEmpty()) {
            response.setResult(false);
            response.setError(errorMessage);
            return response;
        }
        response.setResult(true);
        return response;
    }

    private String getMessageAboutIncorrectSearchData(SearchRequest request) {
        HashMap<Boolean, String> errorMap = new HashMap<>();
        String query = request.getQuery();
        String siteUrl = request.getSiteUrl();
        boolean isInSitesList = sitesList.getSites().stream()
                .anyMatch(site -> site.getUrl().equals(siteUrl));
        errorMap.put(query.isEmpty(), "Задан пустой поисковый запрос");
        errorMap.put(siteUrl.isEmpty(), "Не задана страница поиска");
        errorMap.put(!siteUrl.equals("All") && !isInSitesList, "Указанная страница не найдена");
        for (Boolean error : errorMap.keySet()) {
            if (error) {
                return errorMap.get(true);
            }
        }
        return new String();
    }

    private Set<String> getLemmasFromWords(String text) {
        return lemmaFinder.getLemmasFromWords(text);
    }

    private List<Site> getSitesForSearch(String siteUrl) {
        List<Site> sites = new ArrayList<>();
        if (siteUrl.equals("All")) {
            sites = (ArrayList<Site>) siteRepository.findAll();
        } else {
            Optional<Site> optionalSite = siteRepository.findByUrl(siteUrl);
            if (optionalSite.isPresent()) {
                sites.add(optionalSite.get());
            }
        }
        return sites;
    }

    private List<Lemma> getLemmasEntities(Set<String> lemmas, List<Site> sites) {
        List<Lemma> lemmasEntities = getLemmasEntitiesBySiteInAndLemmaIn(lemmas, sites);
        if (lemmasEntities.isEmpty() || lemmas.size() > lemmasEntities.size()) {
            return new ArrayList<>();
        }
        lemmasEntities = excludeFrequentlyEncounteredLemmas(lemmas, lemmasEntities);
        lemmasEntities = sortLemmasEntitiesByFrequency(lemmasEntities);
        return lemmasEntities;
    }

    private List<Lemma> getLemmasEntitiesBySiteInAndLemmaIn(Set<String> lemmas, List<Site> sites) {
        List<Lemma> lemmasEntities = lemmaRepository.findBySiteInAndLemmaIn(sites, lemmas);
        return lemmasEntities;
    }

    private List<Lemma> excludeFrequentlyEncounteredLemmas(Set<String> lemmas, List<Lemma> lemmasEntities) {
        if (lemmas.size() <= 1) {
            return lemmasEntities;
        }
        int allPagesCount = (int) pageRepository.count();
        List<Lemma> lemmasEntitiesForDelete = lemmasEntities.stream()
                .filter(lemmaEntity -> {
                    int occurrencePercent = lemmaEntity.getFrequency()  * 100 / allPagesCount;
                    return occurrencePercent > FREQUENCY_OCCURRENCE_MAX_PERCENT;
                })
                .toList();
        lemmasEntities.removeAll(lemmasEntitiesForDelete);
        return lemmasEntities;
    }

    private List<Lemma> sortLemmasEntitiesByFrequency(List<Lemma> lemmasEntities) {
        lemmasEntities.sort(Comparator.comparing(l -> l.getFrequency()));
        return lemmasEntities;
    }

    private SearchData[] getSearchDataArray(List<Page> pages, Set<String> lemmas, SearchRequest request) throws IOException {
        int offset = request.getOffset();
        int limit = request.getLimit();
        List<SearchData> dataList = new ArrayList<>();
        if (pages.isEmpty()) {
            return dataList.toArray(new SearchData[dataList.size()]);
        }
        float maxAbsoluteRelevance = getMaxAbsoluteRelevance(pages);
        pages.sort(Comparator.comparing(page -> page.getPath()));
        for (int i = offset; i < limit + offset && i < pages.size(); i++) {
            Page page = pages.get(i);
            page.setRelevance(page.getAbsoluteRelevance() / maxAbsoluteRelevance);
            SearchData searchData = getSearchData(page, lemmas);
            dataList.add(searchData);
        }
        dataList.sort(SearchData::compareTo);
        return dataList.toArray(new SearchData[dataList.size()]);
    }

    private float getMaxAbsoluteRelevance(List<Page> pages) {
        return pages.stream()
                .map(page -> page.getAbsoluteRelevance())
                .max(Float::compare)
                .get();
    }

    private SearchData getSearchData(Page page, Set<String> lemmas) {
        Site site = page.getSite();
        String text = getTextFromHTMLContent(page.getContent());
        String title = HtmlParser.getTitleFromHTMLContent(page.getContent());
        SearchData searchData = new SearchData();
        searchData.setSite(site.getUrl());
        searchData.setSiteName(site.getName());
        searchData.setUri(page.getPath());
        searchData.setTitle((title.isEmpty() ? page.getPath() : title));
        searchData.setSnippet(getSnippetText(lemmas, text));
        searchData.setRelevance(page.getRelevance());
        return searchData;
    }

    public static String getTextFromHTMLContent(String htmlContent) {
        return HtmlParser.getTextFromHTMLContent(htmlContent);

    }

    private String getSnippetText(Set<String> lemmas, String text) {
        setMaxLengthOfSnippetPhrase(lemmas.size());
        HashMap<String, Integer> phrasesRelevanceMap = getPhrasesRelevanceMap(lemmas, text);
        return getSnippetTextBuilder(phrasesRelevanceMap).toString();
    }

    private void setMaxLengthOfSnippetPhrase(int lemmasSize) {
        if (lemmasSize == 0) {
            return;
        }
        maxLengthOfSnippetPhrase = MAX_SEARCH_RESULT_LENGTH / lemmasSize;
    }

    private HashMap<String, Integer> getPhrasesRelevanceMap(Set<String> lemmas, String text) {
        List<String> wordsFromText = Arrays.asList(text.split("\\s"));
        List<String> lemmasOfWordsFromText = lemmaFinder.getLemmasListFromWordsList(wordsFromText);
        List<Integer> indexesOfWords = new ArrayList<>();
        HashMap<String, Integer> phrasesRelevanceMap = new HashMap<>();
        for (String lemma : lemmas) {
            HashMap<String, Integer> currentLemmaPhraseRelevanceMap = new HashMap<>();
            for (Integer indexOfWord : getIndexesOfWordsFromTextByLemma(lemma,  lemmasOfWordsFromText)) {
                String allSearchResultPhrases = phrasesRelevanceMap.keySet().toString();
                if (indexesOfWords.contains(indexOfWord) && allSearchResultPhrases.contains(wordsFromText.get(indexOfWord))) {
                    continue;
                }
                List<Integer> indexesOfSnippetWords = getIndexesOfSnippetWordsByCurrentSearchWord(indexOfWord.intValue(), wordsFromText);
                SnippetData snippetData = getSnippetData(wordsFromText, lemmasOfWordsFromText, indexesOfSnippetWords);
                HashMap<String, Integer> snippetAndHisRelevance = getSnippetAndHisRelevance(snippetData, lemmas);
                currentLemmaPhraseRelevanceMap.putAll(snippetAndHisRelevance);
                indexesOfWords.addAll(indexesOfSnippetWords);
            }
            phrasesRelevanceMap = putTheMostRelevantSnippetPhraseToPhrasesRelevanceMap(currentLemmaPhraseRelevanceMap, phrasesRelevanceMap);
        }
        return phrasesRelevanceMap;
    }

    private Set<Integer> getIndexesOfWordsFromTextByLemma(String lemma, List<String> lemmasOfWordsFromText) {
        Set<Integer> indexesOfWordsByLemma = new HashSet<>();
        for (int i = 0; i < lemmasOfWordsFromText.size(); i++) {
            String lemmaOfWords = lemmasOfWordsFromText.get(i);
            if (lemmaOfWords.equals(lemma)) {
                indexesOfWordsByLemma.add(i);
            }
        }
        return indexesOfWordsByLemma;
    }

    private List<Integer> getIndexesOfSnippetWordsByCurrentSearchWord(int searchWordIndex, List<String> wordsFromText) {
        List<Integer> indexesOfSnippetWords = new ArrayList<>();
        int lengthOfSnippetPhrase = wordsFromText.get(searchWordIndex).length();
        indexesOfSnippetWords.add(searchWordIndex);
        for (int i = 1; lengthOfSnippetPhrase <= maxLengthOfSnippetPhrase; i++) {
            int indexOfPreviousWord = searchWordIndex - i;
            if (indexOfPreviousWord >= 0) {
                indexesOfSnippetWords.add(indexOfPreviousWord);
                lengthOfSnippetPhrase += wordsFromText.get(indexOfPreviousWord).length();
            }
            int indexOfNextWord = searchWordIndex + i;
            if (indexOfNextWord < wordsFromText.size()) {
                indexesOfSnippetWords.add(indexOfNextWord);
                lengthOfSnippetPhrase += wordsFromText.get(indexOfNextWord).length();
            }
        }
        indexesOfSnippetWords.sort(Comparator.comparing(index -> index));
        return indexesOfSnippetWords;
    }

    private SnippetData getSnippetData(List<String> wordsFromText, List<String> lemmasOfWordsFromText, List<Integer> indexesOfSnippetWords) {
        SnippetData snippetData = new SnippetData();
        snippetData.setWordsFromText(wordsFromText);
        snippetData.setLemmasOfWordsFromText(lemmasOfWordsFromText);
        snippetData.setIndexesOfSnippetWords(indexesOfSnippetWords);
        return snippetData;
    }

    private HashMap<String, Integer> getSnippetAndHisRelevance(SnippetData snippetData, Set<String> lemmas) {
        HashMap<String, Integer> relevanceMap = new HashMap<>();
        StringBuilder builder = new StringBuilder();
        int phraseRelevance = 0;
        Set<String> lemmasFromPhrase = new HashSet<>();
        for (int index : snippetData.getIndexesOfSnippetWords()) {
            String word = snippetData.getWordsFromText().get(index);
            String lemmaOfWord = snippetData.getLemmasOfWordsFromText().get(index);
            if (lemmas.contains(lemmaOfWord) && !lemmasFromPhrase.contains(lemmaOfWord)) {
                word = boldWord(word);
                phraseRelevance++;
                lemmasFromPhrase.add(lemmaOfWord);
            }
            builder.append(word + " ");
        }
        builder.append("...");
        relevanceMap.put(builder.toString(), phraseRelevance);
        return relevanceMap;
    }

    private HashMap<String, Integer> putTheMostRelevantSnippetPhraseToPhrasesRelevanceMap(HashMap<String, Integer> currentLemmaPhraseRelevanceMap,
                                                                                          HashMap<String, Integer> phrasesRelevanceMap) {
        for (String snippetPhrase : getSnippetPhrases(currentLemmaPhraseRelevanceMap)) {
            phrasesRelevanceMap.put(snippetPhrase, currentLemmaPhraseRelevanceMap.get(snippetPhrase));
            break;
        }
        return phrasesRelevanceMap;
    }

    private String boldWord(String word) {
        return "<b>" + word + "</b>";
    }

    private StringBuilder getSnippetTextBuilder(HashMap<String, Integer> phrasesRelevanceMap) {
        StringBuilder resultTextBuilder = new StringBuilder();
        Set<String> phrases = getSnippetPhrases(phrasesRelevanceMap);
        for (String phrase : phrases) {
            resultTextBuilder.append(phrase);
            if (resultTextBuilder.length() >= MAX_SEARCH_RESULT_LENGTH) {
                break;
            }
        }
        return resultTextBuilder;
    }

    private Set<String> getSnippetPhrases(HashMap<String, Integer> phrasesRelevanceMap) {
        Set<String> phrases = phrasesRelevanceMap.entrySet()
                .stream()
                .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .collect(Collectors.toMap(Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new))
                .keySet();
        return phrases;
    }

    private Set<String> getLemmasFromLemmaEntities(List<Lemma> lemmaEntities) {
        return lemmaEntities.stream()
                .map(lE -> lE.getLemma())
                .collect(Collectors.toSet());
    }

    private List<Page> getMatchingPages(List<Lemma> lemmaEntities, int lemmasSize, List<Site> sites) {
        List<Page> matchingPages = new ArrayList<>();
        for (Site site : sites) {
            List<Lemma> siteLemmasEntities = lemmaEntities.stream().filter(l -> l.getSite().equals(site)).toList();
            if (siteLemmasEntities.isEmpty() || lemmasSize > siteLemmasEntities.size()) {
                continue;
            }
            List<Page> firstLemmaPages = getFirstLemmaPages(siteLemmasEntities.get(0));
            HashMap<Page, Integer> pagesOccurrenceInLemmas = getPagesOccurrenceInLemmas(siteLemmasEntities, firstLemmaPages);

            firstLemmaPages.stream()
                    .filter(page -> pagesOccurrenceInLemmas.get(page) == siteLemmasEntities.size())
                    .forEach(page -> matchingPages.add(page));

        }
        return matchingPages;
    }

    private List<Page> getFirstLemmaPages(Lemma firstLemma) {
        List<Page> firstLemmaPages = new ArrayList<>();
        firstLemma.getIndexes().forEach(index -> {
            firstLemmaPages.add(index.getPage());
        });
        return firstLemmaPages;
    }

    private HashMap<Page, Integer> getPagesOccurrenceInLemmas(List<Lemma> lemmaEntities, List<Page> firstLemmaPages) {
        HashMap<Page, Integer> pagesOccurrenceInLemmas = new HashMap<>();
        for (Lemma lemmaEntity : lemmaEntities) {
            lemmaEntity.getIndexes().stream()
                    .filter(index -> firstLemmaPages.contains(index.getPage()))
                    .map(index -> {
                        Page page = index.getPage();
                        float absoluteRelevance = page.getAbsoluteRelevance();
                        page.setAbsoluteRelevance(absoluteRelevance + index.getRank());
                        return page;
                    })
                    .forEach(page -> {
                        if (pagesOccurrenceInLemmas.containsKey(page)) {
                            pagesOccurrenceInLemmas.put(page, pagesOccurrenceInLemmas.get(page) + 1);
                        } else {
                            pagesOccurrenceInLemmas.put(page, 1);
                        }
                    });
        }
        return pagesOccurrenceInLemmas;
    }
}
