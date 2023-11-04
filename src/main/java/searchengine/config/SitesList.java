package searchengine.config;

import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import searchengine.model.Site;

import java.util.List;

@Component
@Setter
@ConfigurationProperties(prefix = "indexing-settings")
public class SitesList {

    private List<Site> sites;

    public List<Site> getSites() {
        formatUrl(sites);
        return sites;
    }

    private void formatUrl(List<Site> sites) {
        String regex = "www\\.";
        for (Site site : sites) {
            String url = site.getUrl();
            if (url.contains(regex)) {
                url = url.replaceFirst(regex, "");
                site.setUrl(url);
            }
        }
    }

    public int getSitesCount() {
        return sites.size();
    }
}
