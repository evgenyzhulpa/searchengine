package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.config.SearchBot;
import searchengine.config.SitesList;
import searchengine.dto.indexing.*;
import searchengine.model.*;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {
    @Autowired
    private final SiteRepository siteRepository;
    @Autowired
    private final PageRepository pageRepository;
    @Autowired
    private final LemmaRepository lemmaRepository;
    @Autowired
    private final IndexRepository indexRepository;
    private final SitesList sitesList;
    private final SearchBot bot;
    private final Logger logger = LogManager.getLogger("indexingServiceLogger");
    private boolean performingIndexing;
    private boolean singlePageIndexing;
    private ThreadPoolExecutor executor;
    private ForkJoinPool forkJoinPool;
    private LemmaFinder lemmaFinder;
    private static final int FREQUENCY_OCCURRENCE_MAX_PERCENT = 90;
    private static final int MAX_PAGE_LIST_SIZE = 1000;
    private static final int MAX_SEARCH_RESULT_LENGTH = 200;

    {
        try {
            lemmaFinder = LemmaFinder.getInstance();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public IndexingResponse startIndexing() {
        IndexingResponse response = new IndexingResponse();
        if (performingIndexing) {
            response.setError("Индексация уже запущена");
            response.setResult(false);
            return response;
        }
        logger.info("Start indexing");
        performingIndexing = true;
        runSitesCrawling();
        response.setResult(true);
        return response;
    }

    private void runSitesCrawling() {
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(sitesList.getSitesCount());
        for (Site site : sitesList.getSites()) {
            executor.execute(() -> {
                deleteSiteDataFromDB(site);
                site.setLastError("");
                site.setStatus(SiteStatus.INDEXING);
                site.setStatusTime(LocalDateTime.now());
                runPagesCrawling(saveSiteInDB(site));
            });
        }
    }

    private void deleteSiteDataFromDB(Site site) {
        Optional<Site> siteOptional = siteRepository.findByUrl(site.getUrl());
        if (siteOptional.isPresent()) {
            Site siteEntity = siteOptional.get();
            deleteSiteLemmasFromDB(siteEntity);
            deleteSiteFromDB(siteEntity);
        }
    }

    public void deleteSiteLemmasFromDB(Site site) {
        List<Lemma> lemmaList = lemmaRepository.findBySite(site);
        if (!lemmaList.isEmpty()) {
            lemmaRepository.deleteAll(lemmaList);
        }
    }

    public void deleteSiteFromDB(Site site) {
        siteRepository.delete(site);
    }

    private Site saveSiteInDB(Site site) {
        return siteRepository.save(site);
    }

    private void runPagesCrawling(Site site) {
        try {
            List<Page> pages = getRecursivePageCrawlingResult(site);
            if (pages.size() > 0) {
                checkAndSavePagesToDB(pages, site);
            }
            if (!performingIndexing) {
                handleIndexingError(site, "Индексация прервана пользователем");
            } else {
                SiteStatus status = site.getLastError().isEmpty() ? SiteStatus.INDEXED : SiteStatus.FAILED;
                updateStatusAndStatusTime(site, status);
            }
        } catch (Exception exception) {
            logger.error("Indexing error site " + site.getUrl() + ": " + exception.getMessage());
            Thread.currentThread().interrupt();
            handleIndexingError(site, exception.getMessage());
        }
        logger.info("Site " + site.getUrl() + ": indexing completed.");
    }

    private List<Page> getRecursivePageCrawlingResult(Site site) {
        forkJoinPool = new ForkJoinPool();
        Set<String> links = new HashSet<>();
        String link = site.getUrl() + "/";

        links.add(link);
        RecursiveTask<List<Page>> recursiveTask = getIndexRecursiveTask(links, link, site);
        return forkJoinPool.invoke(recursiveTask);
    }

    private RecursiveTask<List<Page>> getIndexRecursiveTask(Set<String> links, String link, Site site) {
        return new RecursiveTask() {
            @Override
            protected List<Page> compute() {
                try {
                    if (!performingIndexing) {
                        Thread.currentThread().interrupt();
                        return new ArrayList<>();
                    }
                    return getSitePagesList(links, link, site);
                } catch (Exception e) {
                    logger.error(e.getMessage());
                    return new ArrayList<>();
                }
            }
        };
    }

    private List<Page> getSitePagesList(Set<String> links, String link, Site site) throws IOException, InterruptedException {
        List<Page> pages = addNewPageToEmptyList(link, site);
        pages = addAllChildPages(pages, links);
        if (pages.size() > MAX_PAGE_LIST_SIZE) {
            checkAndSavePagesToDB(pages, site);
        }
        return pages;
    }

    private List<Page> addNewPageToEmptyList(String link, Site site) throws IOException, InterruptedException {
        List<Page> pages = new ArrayList<>();
        Page page = getNewPage(link, site);
        pages.add(page);
        return pages;
    }

    private Page getNewPage(String url, Site site) throws IOException, InterruptedException {
        Document document = getHtmlDocumentByUrl(url);
        Page page = new Page();
        page.setSite(site);
        page.setPath(url.replaceFirst(site.getUrl(), ""));
        page.setCode(getPageStatusCode(document));
        page.setContent(getPageContent(document));
        page.setChildLinks(getChildLinksOfDocument(document, site.getUrl()));
        return page;
    }

    private Document getHtmlDocumentByUrl(String url) throws IOException, InterruptedException {
        Thread.sleep(1500);
        return HtmlParser.getDocumentByUrl(url, bot);
    }

    private int getPageStatusCode(Document document) {
        return HtmlParser.getStatusCode(document);
    }

    private String getPageContent(Document document) {
        return HtmlParser.getContent(document);
    }

    private Set<String> getChildLinksOfDocument(Document document, String siteUrl) {
        Set<String> links = new HashSet<>();
        if (!performingIndexing) {
            Thread.currentThread().interrupt();
            return links;
        }
        Elements elements = HtmlParser.getHrefElements(document);
        for (Element element : elements) {
            String link = element.absUrl("href");
            if (isSiteLink(link, siteUrl)) {
                links.add(link);
            }
        }
        return links;
    }

    private boolean isSiteLink(String link, String siteUrl) {
        String regexString2 = siteUrl + "[^:,.#&%?\s]+";
        String regexString3 = siteUrl + "[^:,#&%?\s]+\\.html";
        String regex = regexString2 + "|" + regexString3;
        return link.matches(regex);
    }

    private List<Page> addAllChildPages(List<Page> pages, Set<String> links) {
        if (!performingIndexing) {
            Thread.currentThread().interrupt();
            return pages;
        }
        List<RecursiveTask> taskList = getRecursiveTaskList(pages.get(0), links);
        for (RecursiveTask<List<Page>> task : taskList) {
            pages.addAll(task.join());
        }
        return pages;
    }

    private List<RecursiveTask> getRecursiveTaskList(Page page, Set<String> links) {
        List<RecursiveTask> taskList = new ArrayList<>();
        if (!performingIndexing) {
            Thread.currentThread().interrupt();
            return taskList;
        }
        for (String childLink : page.getChildLinks()) {
            if (links.contains(childLink)) {
                continue;
            }
            links.add(childLink);
            RecursiveTask<List<Page>> task = getIndexRecursiveTask(links, childLink, page.getSite());
            task.fork();
            taskList.add(task);
        }
        return taskList;
    }

    private synchronized void checkAndSavePagesToDB(List<Page> pages, Site site) {
        List<String> pathsOfPages = pages.stream().map(p -> p.getPath()).toList();
        List<String> pathsOfPagesFromDB = pageRepository.findPathByPathInAndSite(pathsOfPages, site);
        if (!performingIndexing) {
            Thread.currentThread().interrupt();
            return;
        }
        List<Page> pagesForDelete = pages.stream().filter(p -> pathsOfPagesFromDB.contains(p.getPath())).toList();
        pages.removeAll(pagesForDelete);
        if (pages.size() > 0) {
            pages = (List<Page>) pageRepository.saveAll(pages);
            addNewPagesIndexingData(pages, site);
        }
    }

    private void addNewPagesIndexingData(List<Page> pages, Site site) throws RuntimeException {
        List<Lemma> lemmaEntities = lemmaRepository.findBySite(site);
        List<Index> indexEntities = new ArrayList<>();
        for (Page page : pages) {
            String text = getTextFromHTMLContent(page.getContent());
            HashMap<String, Integer> lemmasRanks = getLemmasAndTheirFrequenciesFromText(text);
            for (String lemma : lemmasRanks.keySet()) {
                if (!performingIndexing && !singlePageIndexing) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Lemma lemmaEntity = getEntityLemmaFromStringLemma(lemmaEntities, site, lemma);
                if (!lemmaEntities.contains(lemmaEntity)) {
                    lemmaEntities.add(lemmaEntity);
                }
                Index index = getNewIndex(page, lemmaEntity, lemmasRanks.get(lemma));
                indexEntities.add(index);
            }
        }
        lemmaRepository.saveAll(lemmaEntities);
        indexRepository.saveAll(indexEntities);
    }

    public static String getTextFromHTMLContent(String htmlContent) {
        return HtmlParser.getTextFromHTMLContent(htmlContent);

    }

    private HashMap<String, Integer> getLemmasAndTheirFrequenciesFromText(String content) {
        return lemmaFinder.getLemmasAndTheirFrequencies(content);
    }

    private Lemma getEntityLemmaFromStringLemma(List<Lemma> lemmaEntities, Site site, String lemma) {
        Lemma lemmaEntity = new Lemma();
        Optional<Lemma> lemmaOptional = lemmaEntities.stream()
                .filter(l -> l.getLemma().equals(lemma)).findAny();

        if (lemmaOptional.isPresent()) {
            lemmaEntity = lemmaOptional.get();
        } else {
            lemmaEntity.setLemma(lemma);
            lemmaEntity.setSite(site);
        }
        lemmaEntity.setFrequency(lemmaEntity.getFrequency() + 1);
        return lemmaEntity;
    }

    private Index getNewIndex(Page page, Lemma lemma, float rank) {
        Index index = new Index();
        index.setPage(page);
        index.setLemma(lemma);
        index.setRank(rank);
        return index;
    }

    public void updateStatusAndStatusTime(Site site, SiteStatus status) {
        site.setStatusTime(LocalDateTime.now());
        site.setStatus(status);
        siteRepository.save(site);
    }

    public void handleIndexingError(Site site, String error) {
        site.setStatus(SiteStatus.FAILED);
        site.setLastError(error);
        siteRepository.save(site);
    }

    @Override
    public IndexingResponse stopIndexing() {
        IndexingResponse response = new IndexingResponse();
        if (!performingIndexing) {
            response.setResult(false);
            response.setError("Индексация не запущена");
            return response;
        }
        logger.info("Stop indexing");
        stopThreads();
        performingIndexing = false;
        response.setResult(true);

        return response;
    }

    private void stopThreads() {
        forkJoinPool.shutdownNow();
        executor.shutdownNow();
    }

    @Override
    public IndexingResponse indexPage(String url) {
        IndexingResponse response = new IndexingResponse();
        Optional<Site> optionalSite = getPageSiteByUrl(url);
        if (!optionalSite.isPresent()) {
            response.setResult(false);
            response.setError("Данная страница не входит в состав сайтов," +
                    " указанных в конфигурационном файле");
            return response;
        }
        logger.info("Index page " + url);
        singlePageIndexing = true;
        response = getIndexingResponse(optionalSite.get(), url);
        logger.info("Index page " + url + ": completed");
        singlePageIndexing = false;
        response.setResult(true);
        return response;
    }

    private Optional<Site> getPageSiteByUrl(String url) {
        if (!url.isBlank()) {
            return sitesList.getSites()
                    .stream()
                    .filter(site -> url.contains(site.getUrl()))
                    .findAny();
        }
        return Optional.empty();
    }

    private IndexingResponse getIndexingResponse(Site siteObject, String url) {
        IndexingResponse response = new IndexingResponse();
        response.setResult(true);
        try {
            Site site = getSiteDBEntityFromSiteObject(siteObject);
            findAndDeleteOldPageIndexingData(url, site);
            Page page = getNewPage(url, site);
            addNewPageIndexingData(page);
        } catch (Exception e) {
            logger.error("Error index page " + url + ": " + e.getMessage());
            response.setError(e.getMessage());
            response.setResult(false);
            return response;
        }
        return response;
    }

    private Site getSiteDBEntityFromSiteObject(Site site) {
        Optional<Site> siteOptional = siteRepository.findByUrl(site.getUrl());
        if (!siteOptional.isPresent()) {
            site.setStatus(SiteStatus.INDEXED);
            site.setLastError("");
            site.setStatusTime(LocalDateTime.now());
            return siteRepository.save(site);
        }
        return siteOptional.get();
    }

    private void findAndDeleteOldPageIndexingData(String url, Site site) {
        String path = url.replaceFirst(site.getUrl(), "");
        Optional<Page> optionalPage = pageRepository.findByPathAndSite(path, site);
        if (optionalPage.isPresent()) {
            Page page = optionalPage.get();
            List<Lemma> lemmasList = indexRepository.findLemmasByPageId(page);
            pageRepository.delete(page);
            updateDeleteOldLemmaEntities(lemmasList);
        }
    }

    private void updateDeleteOldLemmaEntities(List<Lemma> lemmaEntities) {
        List<Lemma> lemmasListToDelete = new ArrayList<>();
        List<Lemma> lemmasListToUpdate = new ArrayList<>();
        for (Lemma lemma : lemmaEntities) {
            int frequency = lemma.getFrequency() - 1;
            if (frequency == 0) {
                lemmasListToDelete.add(lemma);
                continue;
            }
            lemma.setFrequency(frequency);
            lemmasListToUpdate.add(lemma);
        }
        if (!lemmasListToDelete.isEmpty()) {
            lemmaRepository.deleteAll(lemmasListToDelete);
        }
        if (!lemmasListToUpdate.isEmpty()) {
            lemmaRepository.saveAll(lemmasListToUpdate);
        }
    }

    private void addNewPageIndexingData(Page page) {
        page = getNewPageDBEntity(page);
        List<Page> pages = new ArrayList<>();
        pages.add(page);
        addNewPagesIndexingData(pages, page.getSite());
    }

    private Page getNewPageDBEntity(Page page) {
        return pageRepository.save(page);
    }

    @Override
    public SearchResponse search(SearchRequest request) throws IOException {
        SearchResponse response = getSearchResponseBySearchRequestCorrectness(request);
        if (!response.isResult()) {
            return response;
        }
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
        return response;
    }

    private SearchResponse getSearchResponseBySearchRequestCorrectness(SearchRequest request) {
        SearchResponse response = new SearchResponse();
        String errorMessage = messageAboutIncorrectSearchData(request);
        if (!errorMessage.isEmpty()) {
            response.setResult(false);
            response.setError(errorMessage);
            return response;
        }
        response.setResult(true);
        return response;
    }

    private String messageAboutIncorrectSearchData(SearchRequest request) {
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

    private SearchData getSearchData(Page page, Set<String> lemmas) throws IOException {
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

    private String getSnippetText(Set<String> lemmas, String text) {
        StringBuilder builder = new StringBuilder();
        Set<String> words = lemmaFinder.getWordsByLemmas(lemmas, text);
        for (String word : words) {
            String firstLetterOfWord = word.substring(0, 1);
            String wordWithCapitalLetter = word.replaceFirst(firstLetterOfWord, firstLetterOfWord.toUpperCase(Locale.ROOT));
            String exprOfWord = "(" + wordWithCapitalLetter + "|" + word + ")";
            String regex = "(([-а-яА-Яё0-9()+]*[^А-Яа-я0-9!?.]){1,2}|[^А-Яа-я0-9!?,.])" + exprOfWord + "(([^А-Яа-я0-9!?.][-а-яА-Яё0-9()+]*){1,2}|[^А-Яа-я0-9])";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String searchResult = matcher.group().replaceAll("\n|\s\s", "");
                if (builder.indexOf(searchResult) > -1) {
                    continue;
                }
                if (builder.length() + searchResult.length() >= MAX_SEARCH_RESULT_LENGTH) {
                    break;
                }
                builder.append(searchResult + "... ");
            }
        }
        return boldWordsInText(words, builder.toString());
    }

    private String boldWordsInText(Set<String> words, String text) {
        for (String word : words) {
            String firstLetterOfWord = word.substring(0, 1);
            String wordWithCapitalLetter = word.replaceFirst(firstLetterOfWord, firstLetterOfWord.toUpperCase(Locale.ROOT));
            String exprOfWord = wordWithCapitalLetter + "|" + word;
            Pattern pattern = Pattern.compile(exprOfWord);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                text = text.replace(matcher.group(), "<b>" + matcher.group() + "</b>");
            }
        }
        return text;
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

    private List<Lemma> getLemmasEntitiesBySiteInAndLemmaIn(Set<String> lemmas, List<Site> sites) {
        List<Lemma> lemmasEntities = lemmaRepository.findBySiteInAndLemmaIn(sites, lemmas);
        return lemmasEntities;
    }
}
