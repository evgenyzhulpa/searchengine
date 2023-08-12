package searchengine.model;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Vector;

@Repository
public interface PageRepository extends CrudRepository<Page, Integer> {
    @Query("from Page p where p.path = ?1 and p.site = ?2")
    Optional<Page> findPageByPathAndSite(String path, Site site);
    @Query("select COUNT(*) from Page p where p.site = ?1")
    int getSitePagesCount(Site site);
    @Query("select COUNT(*) from Page p")
    int getAllPagesCount();
    @Query("select p.path from Page p where p.path in ?1 and p.site = ?2")
    Vector<String> getPagePathsByUriListAndSite(List<String> uriList, Site site);

}
