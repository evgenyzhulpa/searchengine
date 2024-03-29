package searchengine.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.dto.search.SearchRequest;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.StatisticsService;
import searchengine.services.SearchService;
import java.io.IOException;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;
    private final SearchService searchService;

    public ApiController(StatisticsService statisticsService, IndexingService indexingService, SearchService searchService) {
        this.statisticsService = statisticsService;
        this.indexingService   = indexingService;
        this.searchService   = searchService;
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<IndexingResponse> startIndexing() {
        return ResponseEntity.ok(indexingService.startIndexing());
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<IndexingResponse> stopIndexing() {
        return ResponseEntity.ok(indexingService.stopIndexing());
    }

    @PostMapping("/indexPage")
    public ResponseEntity<IndexingResponse> indexPage(@RequestParam("url") String url) {
        try {
            return ResponseEntity.ok(indexingService.indexPage(url));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(new IndexingResponse());
        }
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestParam("query") String query,
                                                 @RequestParam(name = "site", required = false,
                                                         defaultValue = "All") String site,
                                                 @RequestParam(name = "offset", defaultValue = "0") int offset,
                                                 @RequestParam(name = "limit", defaultValue = "2") int limit) {
        SearchRequest request = new SearchRequest(query, site, offset, limit);
        return ResponseEntity.ok(searchService.search(request));
    }


}
