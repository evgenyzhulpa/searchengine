package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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
    private boolean startIndexing;
    private boolean stopIndexing;
    private ThreadPoolExecutor executor;
    private ForkJoinPool forkJoinPool;
    private LemmaFinder lemmaFinder;
    private final LocalDateTime emptyDate = LocalDateTime.of(1, 1, 1, 1, 1);
    private static final int FREQUENCY_OCCURRENCE_MAX_PERCENT = 90;

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
        if (startIndexing) {
            response.setError("Индексация уже запущена");
            response.setResult(false);
            return response;
        }
        logger.info("Start indexing");
        startIndexing = true;
        stopIndexing = false;
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
            forkJoinPool = new ForkJoinPool();
            RecursiveTask<LocalDateTime> recursiveTask = getIndexRecursiveTask("", site);

            updateStatusTime(site, forkJoinPool.invoke(recursiveTask));
            if (stopIndexing) {
                handleIndexingError(site, "Индексация остановлена пользователем");
                Thread.currentThread().interrupt();
                return;
            }
            SiteStatus status = site.getLastError().isEmpty() ? SiteStatus.INDEXED : SiteStatus.FAILED;
            updateStatus(site, status);
        } catch (Exception exception) {
            logger.error("Indexing error site " + site.getUrl() + ": " + exception.getMessage());
            Thread.currentThread().interrupt();
            handleIndexingError(site, exception.getMessage());
        }
        logger.info("Site " + site.getUrl() + ": indexing completed.");
    }

    private Document getHtmlDocument(String url) throws IOException, InterruptedException {
        Thread.sleep(1500);
        return HtmlParser.getDocument(url, bot);
    }

    private Page getNewPage(Document document, Site site) throws IOException {
        Page page = new Page();

        page.setSite(site);
        page.setPath(getPagePath(document));
        page.setCode(getPageStatusCode(document));
        page.setContent(getPageContent(document));
        page.setChildLinks(getChildLinksOfDocument(document));
        return page;
    }

    private int getPageStatusCode(Document document) throws IOException {
        return HtmlParser.getStatusCode(document);
    }

    private String getPagePath(Document document) throws IOException {
        return HtmlParser.getPagePath(document);
    }

    private String getPageContent(Document document) throws IOException {
        return HtmlParser.getContent(document);
    }

    private RecursiveTask<LocalDateTime> getIndexRecursiveTask(String link, Site site) {
        return new RecursiveTask() {
            @Override
            protected LocalDateTime compute() {
                try {
                    if (stopIndexing) {
                        handleIndexingError(site, "Индексация остановлена пользователем");
                        Thread.currentThread().interrupt();
                        return emptyDate;
                    }
                    Optional<Page> pageOptional = checkAndSavePageToDB(link, site);
                    if (pageOptional.isPresent()) {
                        Page page = pageOptional.get();
                        runRecursivePageCrawling(page);
                        addNewPagesIndexingData(page);
                    }
                } catch (Exception e) {
                    logger.error("Indexing error site " + site.getUrl() +
                            ", page " + link + ": " + e.getMessage());
                    if (link.isEmpty()) {
                        handleIndexingError(site, e.getMessage());
                    }
                    return emptyDate;
                }
                return LocalDateTime.now();
            }
        };
    }

    private void stopThreads() {
        forkJoinPool.shutdown();
        executor.shutdown();
    }

    private void runRecursivePageCrawling(Page page) throws IOException, InterruptedException {
        Set<String> childLinks = page.getChildLinks();
        Site site = page.getSite();
        ArrayList<RecursiveTask> taskList = getIndexChildLinksTaskList(childLinks, site);

        handleIndexTaskListResults(taskList, site);
    }

    private ArrayList<RecursiveTask> getIndexChildLinksTaskList(Set<String> childLinks, Site site) throws IOException, InterruptedException {
        ArrayList<RecursiveTask> taskList = new ArrayList<>();

        for (String uri : getChildLinks(childLinks, site)) {
            RecursiveTask<LocalDateTime> task = getIndexRecursiveTask(uri, site);
            task.fork();
            taskList.add(task);
        }
        return taskList;
    }

    private void handleIndexTaskListResults(ArrayList<RecursiveTask> taskList, Site site) {
        for (RecursiveTask task : taskList) {
            LocalDateTime dateTime = (LocalDateTime) task.join();
            updateStatusTime(site, dateTime);
        }
    }

    public void updateStatusTime(Site site, LocalDateTime time) {
        if (time.equals(emptyDate)) {
            return;
        }
        site.setStatusTime(time);
        siteRepository.save(site);
    }

    public void updateStatus(Site site, SiteStatus status) {
        site.setStatus(status);
        siteRepository.save(site);
    }

    public void handleIndexingError(Site site, String error) {
        site.setStatus(SiteStatus.FAILED);
        site.setLastError(error);
        siteRepository.save(site);
    }

    public Set<String> getChildLinks(Set<String> childLinks, Site site) {
        Set<String> links = new HashSet<>();
        String parentUrl = site.getUrl();
        String regexProtocol = "http://|https://";
        parentUrl = parentUrl.replaceFirst(regexProtocol, "");

        for (String url : childLinks) {
            url = url.replaceFirst(regexProtocol, "");
            if(isSiteLink(url, parentUrl)) {
                links.add(url.replaceFirst(parentUrl, ""));
            }
        }
        links.removeAll(getDBPagesLinks(links, site));
        return links;
    }

    private Set<String> getChildLinksOfDocument(Document document) throws IOException {
        return HtmlParser.getHtmlElements(document).stream()
                .map(element -> element.absUrl("href"))
                .collect(Collectors.toSet());
    }

    private boolean isSiteLink(String url, String siteUrl) {
        String regexString2 = siteUrl + "[^,.#&%?\s]+";
        String regexString3 = siteUrl + "[^,#&%?\s]+\\.html";
        String regex = regexString2 + "|" + regexString3;

        return url.matches(regex);
    }

    private List<String> getDBPagesLinks(Set<String> links, Site site) {
        List<String> linksFromDB = getPagePathsByUriListAndSite(links.stream().toList(), site);
        return linksFromDB;
    }

    private Vector<String> getPagePathsByUriListAndSite(List<String> uriList, Site site) {
        return pageRepository.getPagePathsByUriListAndSite(uriList, site);
    }

    @Override
    public IndexingResponse stopIndexing() {
        IndexingResponse response = new IndexingResponse();
        if (stopIndexing) {
            response.setResult(false);
            response.setError("Индексация не запущена");
            return response;
        }
        logger.info("Stop indexing");
        stopThreads();
        stopIndexing = true;
        startIndexing = false;
        response.setResult(true);
        return response;
    }

    @Override
    public IndexingResponse indexPage(String url) {
        IndexingResponse response = new IndexingResponse();
        Optional<Site> optionalSite = getPageSiteByUrl(url);

        if (!optionalSite.isPresent()) {
            response.setResult(false);
            response.setError("Данная страница находится за пределами сайтов," +
                    " указанных в конфигурационном файле");
            return response;
        }
        logger.info("Index page " + url);
        try {
            Site site = getSiteDBEntityFromSiteObject(optionalSite.get());
            findAndDeleteOldPageIndexingData(url, site);
            Page page = getNewPage(getHtmlDocument(url), site);
            page = getNewPageDBEntity(page);
            addNewPagesIndexingData(page);
        } catch (Exception e) {
            logger.error("Error index page " + url + ": " + e.getMessage());
            response.setError(e.getMessage());
            response.setResult(false);
            return response;
        }
        logger.info("Index page " + url + ": completed");
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
        String path = "/" + url.replaceFirst(site.getUrl(), "");
        Optional<Page> optionalPage = pageRepository.findPageByPathAndSite(path, site);

        if (optionalPage.isPresent()) {
            Page page = optionalPage.get();
            List<Lemma> lemmasList = indexRepository.findLemmasByPageId(page);
            pageRepository.delete(page);
            updateDeleteOldLemmaEntities(lemmasList);
        }
    }

    private Optional<Page> checkAndSavePageToDB(String link, Site site) throws IOException, InterruptedException {
        String url = site.getUrl() + link;
        Page page = getNewPage(getHtmlDocument(url), site);
        synchronized (pageRepository) {
            if (pageRepository.findPageByPathAndSite(page.getPath(), site).isEmpty()) {
                return Optional.of(getNewPageDBEntity(page));
            }
            return Optional.empty();
        }
    }

    private Page getNewPageDBEntity(Page page) {
        return pageRepository.save(page);
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

    private void addNewPagesIndexingData(Page page) throws IOException, RuntimeException {
        addNewLemmasAndIndexesToDB(page);
    }

    private HashMap<String, Integer> getLemmasFromText(String content) throws IOException {
       return lemmaFinder.getLemmas(content);
    }

    private void addNewLemmasAndIndexesToDB(Page page) throws IOException {
        Site site = page.getSite();
        HashMap<String, Integer> lemmas = getLemmasFromText(page.getContent());

        for (String lemma : lemmas.keySet()) {
            if (stopIndexing) {
                handleIndexingError(site, "Индексация остановлена пользователем");
                Thread.currentThread().interrupt();
                return;
            }
            Lemma lemmaEntity = getEntityLemmaFromStringLemma(site, lemma);
            Index index = new Index();
            index.setPage(page);
            index.setLemma(lemmaEntity);
            index.setRank(lemmas.get(lemma));
            indexRepository.save(index);
        }
    }

    private synchronized Lemma getEntityLemmaFromStringLemma(Site site, String lemma) {
        Optional<Lemma> lemmaOptional = lemmaRepository.findBySiteAndLemma(site, lemma);
        Lemma lemmaObject = new Lemma();

        if (lemmaOptional.isPresent()) {
            lemmaObject = lemmaOptional.get();
        } else {
            lemmaObject.setLemma(lemma);
            lemmaObject.setSite(site);
        }
        lemmaObject.setFrequency(lemmaObject.getFrequency() + 1);
        return lemmaRepository.save(lemmaObject);
    }

    @Override
    public SearchResponse search(SearchRequest request) throws IOException {
        SearchResponse response = new SearchResponse();
        String errorMessage = messageAboutIncorrectSearchData(request);

        if (!errorMessage.isEmpty()) {
            response.setResult(false);
            response.setError(errorMessage);
            return response;
        }
        Set<String> lemmas = getLemmasFromText(request.getQuery()).keySet();
        List<Lemma> lemmaEntities = getLemmaEntities(lemmas, request.getSiteUrl());
        lemmaEntities = excludeFrequentlyEncounteredLemmas(lemmaEntities);
        lemmas = getLemmasFromLemmaEntities(lemmaEntities);
        if (lemmaEntities.isEmpty()) {
            response.setResult(false);
            response.setError("Информация в базе отсутствует");
            return response;
        }
        List<Page> matchingPages = getMatchingPages(lemmaEntities);
        response.setResult(true);
        response.setCount(matchingPages.size());
        if (matchingPages.size() > 0) {
            response.setData(getSearchDataArray(matchingPages, lemmas, request));
        }
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
            if (error) return errorMap.get(true);
        }
        return new String();
    }

    private List<Lemma> excludeFrequentlyEncounteredLemmas(List<Lemma> lemmasEntities) {
        if (lemmasEntities.size() <= 1) {
            return lemmasEntities;
        }

        List<Lemma> lemmasEntities2 = lemmasEntities.stream()
                .filter(lemmaEntity -> {
                    int pagesCount = lemmaEntity.getSite().getPages().size();
                    int occurrencePercent = lemmaEntity.getFrequency()  * 100 / pagesCount;
                    return occurrencePercent > FREQUENCY_OCCURRENCE_MAX_PERCENT;
                })
                .toList();
        lemmasEntities.removeAll(lemmasEntities2);

        return lemmasEntities;
    }

    private List<Lemma> getLemmaEntities(Set<String> lemmas, String siteUrl) {
        List<Lemma> lemmaEntities;

        if (siteUrl.equals("All")) {
            lemmaEntities = lemmaRepository.findByLemmaIn(lemmas);
        } else {
            Site site = siteRepository.findByUrl(siteUrl).get();
            lemmaEntities = lemmaRepository.findBySiteAndLemmaIn(site, lemmas);
        }
        return lemmaEntities;
    }

    private Set<String> getLemmasFromLemmaEntities(List<Lemma> lemmaEntities) {
        return lemmaEntities.stream()
                .map(lE -> lE.getLemma())
                .collect(Collectors.toSet());
    }

    private List<Page> getFirstLemmaPages(Lemma firstLemma) {
        List<Page> firstLemmaPages = new ArrayList<>();

        firstLemma.getIndexes().forEach(index -> {
            firstLemmaPages.add(index.getPage());
        });
        return firstLemmaPages;
    }

    private List<Page> getMatchingPages(List<Lemma> lemmaEntities) {
        List<Page> matchingPages = new ArrayList<>();
        Set<Site> sites = lemmaEntities.stream().map(lE -> lE.getSite()).collect(Collectors.toSet());

        for (Site site : sites) {
            List<Lemma> siteLemmas = lemmaEntities.stream().filter(lE -> lE.getSite() == site).toList();
            List<Page> firstLemmaPages = getFirstLemmaPages(siteLemmas.get(0));
            HashMap<Page, Integer> pagesOccurrenceInLemmas = getPagesOccurrenceInLemmas(siteLemmas, firstLemmaPages);

            firstLemmaPages.stream()
                    .filter(page -> pagesOccurrenceInLemmas.get(page) == siteLemmas.size())
                    .forEach(page -> matchingPages.add(page));

        }
        return matchingPages;
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

    private float getMaxAbsoluteRelevance(List<Page> pages) {
        return pages.stream()
                .map(page -> page.getAbsoluteRelevance())
                .max(Float::compare)
                .get();
    }

    private SearchData getSearchData(Page page, Set<String> lemmas) throws IOException {
        Site site = page.getSite();
        Document document = Jsoup.parse(page.getContent());
        String title = document.title();
        String text = lemmaFinder.clearHtmlTags(page.getContent()).replaceAll("\n", "");
        Set<String> wordsByLemmas = lemmaFinder.wordsByLemmas(text, lemmas);
        SearchData searchData = new SearchData();

        searchData.setSite(site.getUrl());
        searchData.setSiteName(site.getName());
        searchData.setUri(page.getPath());
        searchData.setTitle((title.isEmpty() ? page.getPath() : title));
        searchData.setSnippet(getSnippetText(wordsByLemmas, text));
        searchData.setRelevance(page.getRelevance());
        return searchData;
    }

    private String getSnippetText(Set<String> words, String text) throws IOException {
        StringBuilder builder = new StringBuilder();

        for (String word : words) {
            String regex = "[^.\n\s]*\s" + word + "\s[^.\n\s]*\s";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String searchResult = matcher.group().trim();
                if (builder.length() + searchResult.length() >= 200) {
                    break;
                }
                if (builder.indexOf(searchResult) > -1) {
                    continue;
                }
                builder.append(searchResult + "... ");
            }
        }
        return boldWordsInText(words, builder.toString());
    }

    private String boldWordsInText(Set<String> words, String text) {
        for (String word : words) {
            Pattern pattern = Pattern.compile(word);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                text = text.replace(word, "<b>" + word + "</b>");
            }
        }
        return text;
    }

    private SearchData[] getSearchDataArray(List<Page> pages, Set<String> lemmas, SearchRequest request) throws IOException {
        int offset = request.getOffset();
        int limit = request.getLimit();
        List<SearchData> dataList = new ArrayList<>();
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
}
