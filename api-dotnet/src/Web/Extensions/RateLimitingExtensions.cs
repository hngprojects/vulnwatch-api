using System.Threading.RateLimiting;
using Domain.Common;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Extensions.Options;

namespace Web.Extensions;

public class RateLimitConfig
{
    public const string SectionName = "RateLimit";

    public PolicyConfig Auth { get; set; } = new();
    public PolicyConfig General { get; set; } = new();

    public class PolicyConfig
    {
        public int PermitLimit { get; set; } = 10;
        public int WindowSeconds { get; set; } = 60;
    }
}
public static class RateLimitExtensions
{
    public const string AuthPolicy = "auth-limit";
    public const string GeneralPolicy = "general-limit";

    public static IServiceCollection AddAppRateLimiting(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        var config = configuration
            .GetSection(RateLimitConfig.SectionName)
            .Get<RateLimitConfig>() ?? new RateLimitConfig();

        services.Configure<RateLimitConfig>(
            configuration.GetSection(RateLimitConfig.SectionName));

        services.AddRateLimiter(options =>
        {
            options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;

            options.OnRejected = async (context, cancellationToken) =>
            {
                var response = context.HttpContext.Response;
                response.ContentType = "application/json";

                if (context.Lease.TryGetMetadata(MetadataName.RetryAfter, out var retryAfter))
                    response.Headers.RetryAfter = ((int)retryAfter.TotalSeconds).ToString();

                var jsonOptions = context.HttpContext.RequestServices
                    .GetRequiredService<IOptions<JsonOptions>>()
                    .Value.JsonSerializerOptions;

                var result = Result<object>.Failure(Error.RateLimited("Too many requests. Please slow down."));

                await response.WriteAsJsonAsync(result, jsonOptions, cancellationToken);
            };

            // Auth policy — by IP, since user isn't resolved yet
            options.AddPolicy(AuthPolicy, context =>
            {
                var ip = context.Connection.RemoteIpAddress?.ToString() ?? "unknown";

                return RateLimitPartition.GetSlidingWindowLimiter(
                    $"auth:{ip}",
                    _ => new SlidingWindowRateLimiterOptions
                    {
                        PermitLimit = config.Auth.PermitLimit,
                        Window = TimeSpan.FromSeconds(config.Auth.WindowSeconds),
                        SegmentsPerWindow = 6,
                        QueueProcessingOrder = QueueProcessingOrder.OldestFirst,
                        QueueLimit = 0
                    });
            });

            // General policy — by userId if authed, else by IP
            options.AddPolicy(GeneralPolicy, context =>
            {
                var userId = context.User?.FindFirst(
                    System.Security.Claims.ClaimTypes.NameIdentifier)?.Value;

                var ip = context.Connection.RemoteIpAddress?.ToString() ?? "unknown";

                var key = userId is not null
                    ? $"user:{userId}"
                    : $"ip:{ip}";

                return RateLimitPartition.GetSlidingWindowLimiter(
                    key,
                    _ => new SlidingWindowRateLimiterOptions
                    {
                        PermitLimit = config.General.PermitLimit,
                        Window = TimeSpan.FromSeconds(config.General.WindowSeconds),
                        SegmentsPerWindow = 6,
                        QueueProcessingOrder = QueueProcessingOrder.OldestFirst,
                        QueueLimit = 0
                    });
            });
        });

        return services;
    }
}