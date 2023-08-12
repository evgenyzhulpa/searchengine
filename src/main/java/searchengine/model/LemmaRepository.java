package searchengine.model;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

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
    @Query("from Lemma l where l.lemma in ?1 order by l.frequency, l.lemma")
    List<Lemma> findByLemmaIn(Set<String> lemmas);

    @Query("from Lemma l where l.site = ?1 and l.lemma in ?2 order by l.frequency, l.lemma")
    List<Lemma> findBySiteAndLemmaIn(Site site, Set<String> lemmas);

    @Query("from Lemma l where l.site = ?1 and l.lemma = ?2")
    Optional<Lemma> findBySiteAndLemma(Site site, String lemma);


}
