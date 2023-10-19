package searchengine.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface LemmaRepository extends CrudRepository<Lemma, Integer> {
    List<Lemma> findBySite(Site site);
    int countBySite(Site site);
    List<Lemma> findByLemmaIn(Set<String> lemmas);
    List<Lemma> findBySiteInAndLemmaIn(List<Site> sites, Set<String> lemmas);
    Optional<Lemma> findBySiteAndLemma(Site site, String lemma);

}
