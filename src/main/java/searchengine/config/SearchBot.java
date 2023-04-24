package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "search-settings")
public class SearchBot {
    @Value("${search-settings.user-agent}")
    private String name;
    private String referrer;
}
