using System.Text.Json;
using Domain.Common;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace Web.Extensions;

public static class ResultExtensions
{
    /// <summary>
    /// Converts a <see cref="Result{T}"/> into the correct <see cref="IActionResult"/> so controllers
    /// never need their own if/switch on success or error codes.
    /// <para>
    /// The full result object (<c>IsSuccess</c> + <c>Value</c>/<c>Error</c>) is always the response body,
    /// giving clients a consistent shape regardless of outcome.
    /// </para>
    /// <para>
    /// Usage in a controller action: <c>return result.ToHttpResponse(this);</c>
    /// </para>
    /// </summary>
    public static ActionResult<Result<T>> ToHttpResponse<T>(this Result<T> result, ControllerBase controller) =>
        result.IsSuccess
            ? controller.Ok(result)
            : result.Error!.Code switch
            {
                ErrorCode.NotFound => controller.NotFound(result),
                ErrorCode.Conflict => controller.Conflict(result),
                ErrorCode.Validation => controller.BadRequest(result),
                ErrorCode.Unauthorized => new ObjectResult(result) { StatusCode = StatusCodes.Status401Unauthorized },
                ErrorCode.Forbidden => new ObjectResult(result) { StatusCode = StatusCodes.Status403Forbidden },
                ErrorCode.RateLimited => new ObjectResult(result) { StatusCode = StatusCodes.Status429TooManyRequests },
                _ => new ObjectResult(result) { StatusCode = StatusCodes.Status500InternalServerError },
            };
}


public static class HealthResponse
{
    private static readonly JsonSerializerOptions _jsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    public static async Task WriteAsync(HttpContext context, HealthReport report)
    {
        context.Response.ContentType = "application/json";

        // Map HealthStatus to HTTP status code
        context.Response.StatusCode = report.Status switch
        {
            HealthStatus.Healthy => StatusCodes.Status200OK,
            HealthStatus.Degraded => StatusCodes.Status200OK,
            HealthStatus.Unhealthy => StatusCodes.Status503ServiceUnavailable,
            _ => StatusCodes.Status503ServiceUnavailable
        };

        var response = new
        {
            status = report.Status.ToString(),
            duration = report.TotalDuration.ToString(@"hh\:mm\:ss\.fff"),
            checks = report.Entries.Select(e => new
            {
                name = e.Key,
                status = e.Value.Status.ToString(),
                duration = e.Value.Duration.ToString(@"hh\:mm\:ss\.fff"),
                description = e.Value.Exception?.Message ?? e.Value.Description,
            })
        };

        await context.Response.WriteAsync(JsonSerializer.Serialize(response, _jsonOptions));
    }
}