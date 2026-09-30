package nl.zeekoe.p1mqtt;

public record TopicMessage(String topic, String payload, boolean retain) {

    public TopicMessage(String topic, String payload) {
        this(topic, payload, false);
    }
}
