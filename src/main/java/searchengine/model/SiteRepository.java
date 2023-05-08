package searchengine.model;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SiteRepository extends CrudRepository<Site, Integer> {

    @Query("from Site s where s.url = ?1")
    Optional<Site> findByUrl(String url);

    @Query("from Site")
    List<Site> getSites();
}
