package searchengine.model;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PageRepository extends CrudRepository<Page, Integer> {
    @Query("from Page p where p.site in ?1")
    List<Page> findPagesBySiteIdIn(List<Site> sites);

    @Query("from Page p where p.path = ?1")
    Optional<Page> findPageByPath(String path);
}
