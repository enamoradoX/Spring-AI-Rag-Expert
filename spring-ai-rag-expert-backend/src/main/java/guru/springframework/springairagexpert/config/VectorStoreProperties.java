package guru.springframework.springairagexpert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "sfg.aiapp")
public class VectorStoreProperties {

    private List<Resource> documentsToLoad;


    public List<Resource> getDocumentsToLoad() {
        return documentsToLoad;
    }

    public void setDocumentsToLoad(List<Resource> documentsToLoad) {
        this.documentsToLoad = documentsToLoad;
    }
}
