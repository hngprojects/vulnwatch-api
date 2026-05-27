using Application.Features.Monitoring;
using Application.Features.Monitoring.DTOs;
using Domain.Common;
using MediatR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Web.Extensions;

namespace Web.Controllers;

[EnableRateLimiting(RateLimitExtensions.GeneralPolicy)]
[Authorize]
[ApiController]
[Route("api/[controller]")]
public class MonitoringController(IMediator mediator) : ControllerBase
{
    [HttpGet("domains/{domainId:guid}")]
    public async Task<ActionResult<Result<MonitoringOverviewDto>>> GetOverview(
        Guid domainId,
        CancellationToken ct)
    {
        var result = await mediator.Send(
            new GetMonitoringOverviewQuery(domainId), ct);

        return result.ToHttpResponse(this);
    }

    [HttpGet("domains/{domainId:guid}/settings")]
    public async Task<ActionResult<Result<MonitoringSettingsDto>>> GetSettings(
        Guid domainId,
        CancellationToken ct)
    {
        var result = await mediator.Send(
            new GetDomainSettingsQuery(domainId), ct);

        return result.ToHttpResponse(this);
    }

    [HttpPut("domains/{domainId:guid}/settings")]
    public async Task<ActionResult<Result<MonitoringSettingsDto>>> UpsertSettings(
    Guid domainId,
    [FromBody] UpdateMonitoringSettingsRequest request,
    CancellationToken ct)
    {
        var result = await mediator.Send(new UpdateMonitoringSettingsCommand(
            domainId,
            request.MonitoringEnabled,
            request.ScanFrequency,
            request.SslAlertThresholds,
            request.NotificationChannels), ct);
        return result.ToHttpResponse(this);
    }

    [HttpPatch("domains/{domainId:guid}/settings/toggle")]
    public async Task<ActionResult<Result<MonitoringSettingsDto>>> Toggle(
        Guid domainId,
        [FromQuery] bool enable,
        CancellationToken ct)
    {
        var result = await mediator.Send(
            new ToggleMonitoringCommand(domainId, enable), ct);
        return result.ToHttpResponse(this);
    }
}