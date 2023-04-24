package searchengine.model;

import jakarta.persistence.*;

@Entity
@Table(name = "search_index")
public class Index
{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @ManyToOne
    @JoinColumn(name = "page_id", nullable = false)
    private Page page;
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "lemma_id", nullable = false)
    private Lemma lemma;
    @Column(name = "lemma_rank", nullable = false)
    float rank;

}
