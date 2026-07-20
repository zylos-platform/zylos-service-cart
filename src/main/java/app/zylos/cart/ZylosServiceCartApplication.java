package app.zylos.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ZylosServiceCartApplication {

    static void main(String[] args) {
        SpringApplication.run(ZylosServiceCartApplication.class, args);
    }
}
