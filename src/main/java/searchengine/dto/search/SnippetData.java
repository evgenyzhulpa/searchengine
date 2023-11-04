package searchengine.dto.search;

import lombok.Data;
import java.util.List;

@Data
public class SnippetData {
    private List<String> wordsFromText;
    private List<String> lemmasOfWordsFromText;
    private List<Integer> indexesOfSnippetWords;

}
