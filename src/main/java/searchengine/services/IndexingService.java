package searchengine.services;

import searchengine.dto.indexing.IndexingResponse;

import java.io.IOException;

public interface IndexingService {
    IndexingResponse startIndexing();
    IndexingResponse stopIndexing();
    IndexingResponse indexPage(String url) throws IOException;
}
