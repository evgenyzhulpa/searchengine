package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalField;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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
        List<Site> sites = siteRepository.getSites();

        data.setTotal(getTotalStatistics(sites));
        data.setDetailed(getDetailedStatistics(sites));
        return data;
    }

    private TotalStatistics getTotalStatistics(List<Site> sites) {
        TotalStatistics total = new TotalStatistics();

        total.setSites(sites.size());
        total.setIndexing(true);
        total.setPages(pageRepository.getAllPagesCount());
        total.setLemmas(lemmaRepository.getAllLemmasCount());
        return total;
    }

    private List<DetailedStatisticsItem> getDetailedStatistics(List<Site> sites) {
        List<DetailedStatisticsItem> detailed = new ArrayList<>();

        for(Site site : sites) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();

            item.setName(site.getName());
            item.setUrl(site.getUrl());
            item.setPages(pageRepository.getSitePagesCount(site));
            item.setLemmas(lemmaRepository.getSiteLemmasCount(site));
            item.setStatus(site.getStatus().toString());
            item.setError(site.getLastError());
            item.setStatusTime(site.getStatusTime().toEpochSecond(ZoneOffset.MAX));
            detailed.add(item);
        }
        return detailed;
    }
}
