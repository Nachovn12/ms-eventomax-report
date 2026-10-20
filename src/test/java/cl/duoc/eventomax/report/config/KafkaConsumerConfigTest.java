package cl.duoc.eventomax.report.config;

import cl.duoc.eventomax.report.exception.NonRetryableEventException;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExceptionMatcher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.JacksonException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerConfigTest {

    @Mock
    private KafkaTemplate<String, String> dltKafkaTemplate;

    @Test
    @DisplayName("DefaultErrorHandler clasifica NonRetryableEventException como no reintentable (directo a DLT)")
    void kafkaErrorHandler_classifiesNonRetryableExceptions() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "dltTopic", "productions.events.report.DLT");
        ReflectionTestUtils.setField(config, "maxAttempts", 3);
        ReflectionTestUtils.setField(config, "backoffIntervalMs", 1000L);

        CommonErrorHandler errorHandler = config.kafkaErrorHandler(dltKafkaTemplate);

        assertThat(errorHandler).isInstanceOf(DefaultErrorHandler.class);
        DefaultErrorHandler defaultErrorHandler = (DefaultErrorHandler) errorHandler;

        ExceptionMatcher matcher = (ExceptionMatcher) ReflectionTestUtils.invokeMethod(defaultErrorHandler, "getExceptionMatcher");
        assertThat(matcher).isNotNull();

        // Las excepciones no reintentables deben evaluarse como false (no reintentables -> van a DLT)
        assertThat(matcher.match(new NonRetryableEventException("Evento malformado o inválido"))).isFalse();
        assertThat(matcher.match(new JacksonException("JSON malformado") {})).isFalse();
        assertThat(matcher.match(new IllegalArgumentException("Argumento inválido"))).isFalse();

        // Excepciones transitorias deben evaluarse como true (reintentables)
        assertThat(matcher.match(new RuntimeException("Error transitorio de red"))).isTrue();
        assertThat(matcher.match(new IllegalStateException("Estado transitorio"))).isTrue();
    }

    @Test
    @DisplayName("dltProducerFactory configura serializadores StringSerializer y bootstrapServers")
    void dltProducerFactory_configuresStringSerializers() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");

        ProducerFactory<String, String> factory = config.dltProducerFactory();

        assertThat(factory).isInstanceOf(DefaultKafkaProducerFactory.class);
        Map<String, Object> configs = factory.getConfigurationProperties();
        assertThat(configs.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo("localhost:9092");
        assertThat(configs.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
        assertThat(configs.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
    }

    @Test
    @DisplayName("dltKafkaTemplate se construye a partir del ProducerFactory configurado")
    void dltKafkaTemplate_buildsSuccessfully() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");

        ProducerFactory<String, String> factory = config.dltProducerFactory();
        KafkaTemplate<String, String> template = config.dltKafkaTemplate(factory);

        assertThat(template).isNotNull();
        assertThat(template.getProducerFactory()).isSameAs(factory);
    }
}

