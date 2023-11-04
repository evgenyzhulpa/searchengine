package searchengine.model;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface LemmaRepository extends CrudRepository<Lemma, Integer> {
    List<Lemma> findBySite(Site site);
    int countBySite(Site site);
    List<Lemma> findBySiteInAndLemmaIn(List<Site> sites, Set<String> lemmas);
}
