package searchengine.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;

import java.util.List;

@Repository
public interface IndexRepository extends CrudRepository<Index, Integer> {
    @Query("select i.lemma from Index i where i.page = ?1")
    List<Lemma> findLemmasByPageId(Page page);
}
