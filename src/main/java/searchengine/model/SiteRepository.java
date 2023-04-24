package searchengine.model;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SiteRepository extends CrudRepository<Site, Integer> {

    @Query("from Site s where s.url = ?1")
    Site findByUrl(String url);

    @Query("from Site s where s.url in ?1")
    List<Site> findByUrlIn(List<String> urlList);

    @Query("from Site s where s.status = ?1")
    List<Site> findByStatus(SiteStatus status);

    @Query("from Site")
    List<Site> getSites();
}
