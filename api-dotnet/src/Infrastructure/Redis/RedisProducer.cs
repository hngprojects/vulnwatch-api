using Application.Interfaces;
using StackExchange.Redis;
using System.Text.Json;

namespace Infrastructure.Redis;

public class RedisProducer : IRedisProducer
{
    private readonly IConnectionMultiplexer _redis;

    public RedisProducer(IConnectionMultiplexer redis)
    {
        _redis = redis;
    }

    // public async Task PublishAsync<T>(string channel, T message)
    // {
    //     var db = _redis.GetDatabase();
    //     var json = JsonSerializer.Serialize(message);
    //     await db.PublishAsync(channel, json);
    // }
}
