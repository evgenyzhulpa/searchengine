package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.config.SearchConfiguration;
import searchengine.config.SitesList;
import searchengine.dto.indexing.*;
import searchengine.model.*;
import searchengine.parsers.HtmlParser;
import searchengine.parsers.LemmaFinder;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

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
    private final SearchConfiguration configuration;
    private final Logger logger = LogManager.getLogger("indexingServiceLogger");
    private boolean performingIndexing;
    private boolean singlePageIndexing;
    private ThreadPoolExecutor executor;
    private ForkJoinPool forkJoinPool;
    private LemmaFinder lemmaFinder;
    private static final int MAX_PAGE_LIST_SIZE = 1000;

    {
        try {
            lemmaFinder = LemmaFinder.getInstance();
        } catch (IOException e) {
            logger.error(e.getMessage());
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
        return HtmlParser.getDocumentByUrl(url, configuration);
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
}
