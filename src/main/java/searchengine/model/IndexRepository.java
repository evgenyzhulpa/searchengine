package searchengine.model;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IndexRepository extends CrudRepository<Index, Integer> {
    @Query("from Index i where i.page = ?1")
    List<Index> findIndexesByPageId(Page page);

    @Query("select i.lemma from Index i where i.page = ?1")
    List<Lemma> findLemmasByPageId(Page page);

}
