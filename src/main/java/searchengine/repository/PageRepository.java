package searchengine.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Page;
import searchengine.model.Site;

import java.util.List;
import java.util.Optional;

@Repository
public interface PageRepository extends CrudRepository<Page, Integer> {
    Optional<Page> findByPathAndSite(String path, Site site);

    long count();
    int countBySite(Site site);
    @Query("select p.path from Page p where p.path in ?1 and p.site = ?2")
    List<String> findPathByPathInAndSite(List<String> uriList, Site site);
}
