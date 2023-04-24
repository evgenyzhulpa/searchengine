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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {
    @Autowired
    private final SiteRepository siteRepository;
    @Autowired
    private final PageRepository pageRepository;
    @Autowired
    private final LocalPageRepository localPageRepository;
    private final SitesList sitesList;
    private final SearchBot bot;
    private boolean startIndexing;
    private boolean stopIndexing;
    private static final Logger logger = LogManager.getLogger("indexingServiceLogger");
    private long startTime = System.currentTimeMillis();

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
        deleteConfigurationSitesDataFromDB();
        runSitesCrawling();
        response.setResult(true);
        return response;
    }

    private void deleteConfigurationSitesDataFromDB() {
        List<Site> sites = getConfigurationSitesDataFromDB();
        if (!sites.isEmpty()) {
            deleteSites(sites);
        }
    }

    private List<Site> getConfigurationSitesDataFromDB() {
        List<String> urlList = sitesList.getSites()
                .stream()
                .map(site -> site.getUrl())
                .toList();
        return findByUrlIn(urlList);
    }

    public void deleteSites(List<Site> sites) {
        siteRepository.deleteAll(sites);
    }

    public List<Site> findByUrlIn(List<String> urlList) {
        return siteRepository.findByUrlIn(urlList);
    }

    private void runSitesCrawling() {
        for (Site site : sitesList.getSites()) {
            Thread thread = new Thread(() -> {
                site.setLastError("");
                site.setStatus(SiteStatus.INDEXING);
                site.setStatusTime(LocalDateTime.now());
                runPagesCrawling(saveSiteInDB(site));
            });
            thread.start();
        }
    }

    private Site saveSiteInDB(Site site) {
        return siteRepository.save(site);
    }

    private void runPagesCrawling(Site site) {
        try {
            ForkJoinPool forkJoinPool = new ForkJoinPool();
            Document htmlDocument = getHtmlDocument(site.getUrl());
            Page parentPage = getNewPage(htmlDocument, site);
            RecursiveTask<LocalDateTime> recursiveTask = getRecursiveTask(parentPage);

            updateStatusTime(site, forkJoinPool.invoke(recursiveTask));
            if (stopIndexing) {
                handleIndexingError(site, "Индексация остановлена пользователем");
                Thread.currentThread().interrupt();
                return;
            }
            savePagesInDB(localPageRepository.getPagesOfSite(site));
            localPageRepository.deletePagesAndLinksOfSite(site);
            updateStatus(site, SiteStatus.INDEXED);
        } catch (Exception exception) {
            logger.error(exception.getMessage());
            handleIndexingError(site, exception.getMessage());
            Thread.currentThread().interrupt();
        }
        logger.info("Indexing completed.");
    }

    private Document getHtmlDocument(String url) throws IOException {
        return HtmlParser.getDocument(url, bot);
    }

    private Page getNewPage(Document document, Site site) throws IOException {
        Page page = new Page();

        page.setSite(site);
        page.setPath(getPagePath(document));
        page.setCode(getPageStatusCode(document));
        page.setContent(getPageContent(document));
        page.setHtmlDocument(document);
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

    private RecursiveTask<LocalDateTime> getRecursiveTask(Page parentPage) {
        return new RecursiveTask() {
            @Override
            protected LocalDateTime compute() {
                try {
                    List<RecursiveTask> taskList = new ArrayList<>();

                    if (stopIndexing) {
                        Thread.currentThread().interrupt();
                        return LocalDateTime.now();
                    }
                    if (isInLocalPageRepository(parentPage)) {
                        return LocalDateTime.now();
                    }
                    localPageRepository.addPage(parentPage);
                    for (Page childPage : getChildPages(parentPage)) {
                        RecursiveTask<LocalDateTime> task = getRecursiveTask(childPage);
                        task.fork();
                        taskList.add(task);
                    }
                    for (RecursiveTask task : taskList) {
                        updateStatusTime(parentPage.getSite(), (LocalDateTime) task.join());
                    }
                } catch (Exception e) {
                    logger.error(e.getMessage());
                    return LocalDateTime.now();
                }
                return LocalDateTime.now();
            }
        };
    }

    private boolean isInLocalPageRepository(Page page) {
        return localPageRepository.isInPageList(page);
    }

    public synchronized void updateStatusTime(Site site, LocalDateTime time) {
        site.setStatusTime(time);
        siteRepository.save(site);
    }

    public void savePagesInDB(Collection<Page> pages) {
        pageRepository.saveAll(pages);
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

    public Collection<Page> getChildPages(Page parentPage) throws IOException, InterruptedException {
        ArrayList<Page> pages = new ArrayList<>();
        Site site = parentPage.getSite();
        String parentUrl = site.getUrl();

        Thread.sleep(1500);
        for (Element element : getHtmlElements(parentPage.getHtmlDocument())) {
            String url = element.absUrl("href");

            if(!url.contains("#") && isSiteLink(url, parentUrl) && !localPageRepository.isInPageLinks(url)) {
                pages.add(getNewPage(getHtmlDocument(url), site));
                localPageRepository.addPageLink(url);
            }
        }
        return pages;
    }

    private Elements getHtmlElements(Document document) throws IOException {
        return HtmlParser.getHtmlElements(document);
    }

    private boolean isSiteLink(String url, String siteUrl) {
        String regexString1 = siteUrl + "[^,.\s]+";
        String regexString2 = siteUrl + "[^,\s]+\\.html";
        String regex = regexString1 + "|" + regexString2;

        return url.matches(regex);
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
        stopIndexing = true;
        startIndexing = false;
        response.setResult(true);
        return response;
    }

    @Override
    public IndexingResponse indexPage(String url) {
        IndexingResponse response = new IndexingResponse();
//        PageIndexer pageIndexer = new PageIndexer(siteService, pageService);
//        Optional<Site> optionalSite = null;
//
//        if (!url.isBlank()) {
//            optionalSite = siteService.getSites()
//                    .stream()
//                    .filter(site -> site.getUrl().contains(url))
//                    .findAny();
//        }
//        response.setResult(optionalSite.isPresent());
//        if (!optionalSite.isPresent()) {
//            response.setError("Данная страница находится за пределами сайтов," +
//                    " указанных в конфигурационном файле");
//            return response;
//        }
//        try {
//            pageIndexer.indexPage(url);
//        } catch (IOException e) {
//            response.setError(e.getMessage());
//            response.setResult(false);
//            return response;
//        }
        return response;
    }
}
