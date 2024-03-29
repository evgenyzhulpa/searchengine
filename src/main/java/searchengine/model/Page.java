package searchengine.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.Set;

@Entity
@Table(name = "page")
@Getter
@Setter
public class Page {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @ManyToOne
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;
    @Column(columnDefinition = "TEXT NOT NULL, FULLTEXT KEY idx_page_path (path)")
    private String path;
    private int code;
    @Column(columnDefinition = "MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci", nullable = false)
    private String content;
    @OneToMany(mappedBy = "page", cascade = CascadeType.ALL)
    private Set<Index> indexes;
    @Transient
    private Set<String> childLinks;
    @Transient
    private float absoluteRelevance;
    @Transient
    private float relevance;
}
