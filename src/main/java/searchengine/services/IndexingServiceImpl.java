package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {
    @Autowired
    private final SiteRepository siteRepository;
    @Autowired
    private final PageRepository pageRepository;
    @Autowired
    private final LocalPageRepository localPageRepository;
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
        clearLocalPageRepository();
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

    public synchronized void deleteSiteLemmasFromDB(Site site) {
        List<Lemma> lemmaList = lemmaRepository.findBySite(site);
        if (!lemmaList.isEmpty()) {
            lemmaRepository.deleteAll(lemmaList);
        }
    }

    public synchronized void deleteSiteFromDB(Site site) {
        siteRepository.delete(site);
    }

    private Site saveSiteInDB(Site site) {
        return siteRepository.save(site);
    }

    private void runPagesCrawling(Site site) {
        try {
            forkJoinPool = new ForkJoinPool();
            Document htmlDocument = getHtmlDocument(site.getUrl());
            Page parentPage = getNewPage(htmlDocument, site);
            RecursiveTask<LocalDateTime> recursiveTask = getRecursiveTask(parentPage);

            updateStatusTime(site, forkJoinPool.invoke(recursiveTask));
            if (stopIndexing) {
                handleIndexingError(site, "Индексация остановлена пользователем");
                Thread.currentThread().interrupt();
                return;
            }
            List<Page> pageDBEntities = savePagesInDB(localPageRepository.getPagesOfSite(site));
            //addNewPagesIndexingData(pageDBEntities, site);
            localPageRepository.deletePagesAndLinksOfSite(site);
            updateStatus(site, SiteStatus.INDEXED);
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

    private RecursiveTask<LocalDateTime> getRecursiveTask(Page parentPage) {
        return new RecursiveTask() {
            @Override
            protected LocalDateTime compute() {
                try {
                    List<RecursiveTask> taskList = new ArrayList<>();

                    if (stopIndexing) {
                        forkJoinPool.shutdown();
                        executor.shutdown();
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
                    logger.error("Indexing error site " + parentPage.getSite().getUrl() +
                            ", page " + parentPage.getPath() + ": " + e.getMessage());
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

    public synchronized List<Page> savePagesInDB(Collection<Page> pages) {
        return (List<Page>) pageRepository.saveAll(pages);
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

        for (String url : parentPage.getChildLinks()) {
            if(isSiteLink(url, parentUrl) && !localPageRepository.isInPageLinks(url)) {
                pages.add(getNewPage(getHtmlDocument(url), site));
                localPageRepository.addPageLink(url);
            }
        }
        return pages;
    }

    private Set<String> getChildLinksOfDocument(Document document) throws IOException {
        return HtmlParser.getHtmlElements(document).stream()
                .map(element -> element.absUrl("href"))
                .collect(Collectors.toSet());
    }

    private boolean isSiteLink(String url, String siteUrl) {
        String regexString1 = siteUrl + "[^,.#&%?\s]+";
        String regexString2 = siteUrl + "[^,#&%?\s]+\\.html";
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

    private void clearLocalPageRepository() {
        localPageRepository.deleteAllPagesAndLinks();
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
            Page page = getNewPageDBEntity(getHtmlDocument(url), site);
            addNewPagesIndexingData(new ArrayList<>(Collections.singleton(page)), site);
        } catch (Exception e) {
            logger.error("Error index page " + url + ": " + e.getMessage());
            response.setError(e.getMessage());
            response.setResult(false);
            return response;
        }
        logger.info("Index page " + url + ": completed");
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

    private Page getNewPageDBEntity(Document document, Site site) throws IOException {
        return pageRepository.save(getNewPage(document, site));
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

    private synchronized void addNewPagesIndexingData(List<Page> pages, Site site) throws IOException, RuntimeException {
        getLemmaAndIndexNewDBEntities(pages, site);
    }

    private HashMap<String, Integer> getLemmasOfPageContent(String content) throws IOException {
        LemmaFinder finder = LemmaFinder.getInstance();
        return finder.getLemmas(content);
    }

    private void getLemmaAndIndexNewDBEntities(List<Page> pages, Site site) throws IOException {
        List<Lemma> lemmaEntities = lemmaRepository.findBySite(site);

        for (Page page : pages) {
            HashMap<String, Integer> lemmas = getLemmasOfPageContent(page.getContent());

            for (String lemma : lemmas.keySet()) {
                Lemma lemmaObject = new Lemma();
                Optional<Lemma> lemmaOptional = lemmaEntities.stream()
                        .filter(lemmaEntity -> lemmaEntity.getLemma().equals(lemma))
                        .findAny();

                if (lemmaOptional.isPresent()) {
                    lemmaObject = lemmaOptional.get();
                } else {
                    lemmaObject.setLemma(lemma);
                    lemmaObject.setSite(site);
                }
                lemmaObject.setFrequency(lemmaObject.getFrequency() + 1);
                Lemma lemmaEntity = lemmaRepository.save(lemmaObject);

                Index index = new Index();
                index.setPage(page);
                index.setLemma(lemmaEntity);
                index.setRank(lemmas.get(lemmaEntity.getLemma()));
                indexRepository.save(index);
            }
        }
    }
}
