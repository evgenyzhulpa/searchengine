package searchengine.dto.search;

import lombok.Data;
import org.jetbrains.annotations.NotNull;

@Data
public class SearchData implements Comparable {
    private String site;
    private String siteName;
    private String uri;
    private String title;
    private String snippet;
    private double relevance;

    @Override
    public int compareTo(@NotNull Object o) {
        SearchData searchData = (SearchData) o;
        return - Double.compare(relevance, searchData.getRelevance());
    }

}
