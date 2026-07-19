package app.zylos.cart.adapter.out.persistence.dynamodb;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/**
 * Wires the DynamoDB client. When {@code zylos.dynamodb.endpoint} is set (local/dev against
 * dynamodb-local), the endpoint is overridden and dummy static credentials are used; otherwise the
 * default credential chain applies (IAM role in a real deployment).
 */
@Configuration(proxyBeanMethods = false)
public class DynamoDbConfig {

    @Bean
    DynamoDbClient dynamoDbClient(
            @Value("${zylos.dynamodb.endpoint:}") String endpoint,
            @Value("${zylos.aws.region:us-east-1}") String region) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(region));

        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(
                            StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }

    @Bean
    DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    }
}
