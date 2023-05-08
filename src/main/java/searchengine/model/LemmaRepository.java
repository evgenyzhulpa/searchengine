package searchengine.model;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface LemmaRepository extends CrudRepository<Lemma, Integer> {

    @Query("from Lemma l where l.site = ?1")
    List<Lemma> findBySite(Site site);
    @Query("select COUNT(l.lemma) from Lemma l where l.site = ?1")
    int getSiteLemmasCount(Site site);
    @Query("select COUNT(l.lemma) from Lemma l")
    int getAllLemmasCount();


}
