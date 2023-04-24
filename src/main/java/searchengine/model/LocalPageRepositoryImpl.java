package searchengine.model;

import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class LocalPageRepositoryImpl implements LocalPageRepository{
    private Vector<String> links = new Vector<>();
    private Vector<Page> pages = new Vector<>();

    @Override
    public void addPageLink(String link) {
        links.add(link);
    }

    @Override
    public boolean isInPageLinks(String link) {
        return links.contains(link);
    }

    public List<String> getLinksOfSite(Site site) {
        return links.stream()
                .filter(link -> link.contains(site.getUrl()))
                .toList();
    }

    @Override
    public void addPage(Page page) {
        pages.add(page);
    }

    @Override
    public boolean isInPageList(Page page) {
        return pages.contains(page);
    }

    @Override
    public List<Page> getPagesOfSite(Site site) {
        return pages.stream()
                .filter(page -> page.getSite() == site)
                .toList();
    }

    @Override
    public void deletePagesAndLinksOfSite(Site site) {
        pages.removeAll(getPagesOfSite(site));
        links.removeAll(getLinksOfSite(site));
    }
}
