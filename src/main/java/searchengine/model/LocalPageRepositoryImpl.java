package searchengine.model;

import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class LocalPageRepositoryImpl implements LocalPageRepository{
    private Vector<String> links = new Vector<>();
    private Vector<Page> pages = new Vector<>();

    @Override
    public synchronized void addPageLink(String link) {
        links.add(link);
    }

    @Override
    public synchronized boolean isInPageLinks(String link) {
        return links.contains(link);
    }

    public synchronized List<String> getLinksOfSite(Site site) {
        return links.stream()
                .filter(link -> link.contains(site.getUrl()))
                .toList();
    }

    @Override
    public synchronized void addPage(Page page) {
        pages.add(page);
    }

    @Override
    public synchronized boolean isInPageList(Page page) {
        return pages.contains(page);
    }

    @Override
    public synchronized List<Page> getPagesOfSite(Site site) {
        return pages.stream()
                .filter(page -> page.getSite() == site)
                .toList();
    }

    @Override
    public synchronized void deletePagesAndLinksOfSite(Site site) {
        pages.removeAll(getPagesOfSite(site));
        links.removeAll(getLinksOfSite(site));
    }

    @Override
    public synchronized void deleteAllPagesAndLinks() {
        pages.clear();
        links.clear();
    }
}
