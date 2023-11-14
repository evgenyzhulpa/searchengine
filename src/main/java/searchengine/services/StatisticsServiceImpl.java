package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.*;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {
    @Autowired
    private final SiteRepository siteRepository;
    @Autowired
    private final PageRepository pageRepository;
    @Autowired
    private final LemmaRepository lemmaRepository;

    @Override
    public StatisticsResponse getStatistics() {
        StatisticsResponse response = new StatisticsResponse();

        response.setStatistics(getStatisticsData());
        response.setResult(true);
        return response;
    }

    private StatisticsData getStatisticsData() {
        StatisticsData data = new StatisticsData();
        List<Site> sites = (List<Site>) siteRepository.findAll();
        data.setTotal(getTotalStatistics(sites));
        data.setDetailed(getDetailedStatistics(sites));
        return data;
    }

    private TotalStatistics getTotalStatistics(List<Site> sites) {
        TotalStatistics total = new TotalStatistics();
        total.setSites(sites.size());
        total.setIndexing(true);
        total.setPages((int) pageRepository.count());
        total.setLemmas((int) lemmaRepository.count());
        return total;
    }

    private List<DetailedStatisticsItem> getDetailedStatistics(List<Site> sites) {
        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        for(Site site : sites) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            Timestamp timestamp = Timestamp.valueOf(site.getStatusTime());
            long millis = timestamp.getTime();
            item.setName(site.getName());
            item.setUrl(site.getUrl());
            item.setPages(pageRepository.countBySite(site));
            item.setLemmas(lemmaRepository.countBySite(site));
            item.setStatus(site.getStatus().toString());
            item.setError(site.getLastError());
            item.setStatusTime(millis);
            detailed.add(item);
        }
        return detailed;
    }
}
