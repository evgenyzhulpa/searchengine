package searchengine.model;

import java.util.List;

public interface LocalPageRepository {
    void addPageLink(String link);
    boolean isInPageLinks(String link);
    void addPage(Page page);
    boolean isInPageList(Page page);
    List<Page> getPagesOfSite(Site site);
    void deletePagesAndLinksOfSite(Site site);
    void deleteAllPagesAndLinks();
}
