namespace Application.Interfaces;

public interface IRedisProducer
{
    Task PublishAsync<T>(string channel, T message);
}
