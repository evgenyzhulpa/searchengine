package searchengine.dto.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
public class SearchResponse {
    private boolean result;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private int count;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private SearchData[] data;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;

}
