using Application.Features.Dashboard;
using Application.Features.Dashboard.DTOs;
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
public class DashboardController(IMediator mediator) : ControllerBase
{
    /// <summary>
    /// Overall Stats
    /// Call on every dashboard load.
    /// </summary>
    [HttpGet("summary")]
    public async Task<ActionResult<Result<DashboardSummaryDto>>> GetSummary(
        CancellationToken ct)
    {
        var result = await mediator.Send(
            new GetDashboardSummaryQuery(), ct);
        return result.ToHttpResponse(this);
    }

    /// <summary>
    /// Paginated domain rows for the domains table.
    /// Default page size 10 — enough for the dashboard view.
    /// </summary>
    [HttpGet("domains")]
    public async Task<ActionResult<Result<PagedResult<DashboardDomainRowDto>>>> GetDomains(
        [FromQuery] int page     = 1,
        [FromQuery] int pageSize = 10,
        CancellationToken ct = default)
    {
        var result = await mediator.Send(
            new GetDashboardDomainsQuery(page, pageSize), ct);
        return result.ToHttpResponse(this);
    }

    /// <summary>
    /// Recent alerts across all domains — newest first.
    /// Default limit 10 for the dashboard feed.
    /// </summary>
    [HttpGet("alerts")]
    public async Task<ActionResult<Result<IReadOnlyList<DashboardAlertDto>>>> GetAlerts(
        [FromQuery] int limit = 10,
        CancellationToken ct = default)
    {
        var result = await mediator.Send(
            new GetDashboardAlertsQuery(limit), ct);
        return result.ToHttpResponse(this);
    }

    // /// <summary>
    // /// Priority action list for the "needs attention" panel.
    // /// Derived from domain + SSL + findings state — no extra data required.
    // /// </summary>
    // [HttpGet("attention")]
    // public async Task<ActionResult<Result<IReadOnlyList<AttentionItemDto>>>> GetAttention(
    //     CancellationToken ct = default)
    // {
    //     var result = await mediator.Send(
    //         new GetDashboardAttentionQuery(), ct);
    //     return result.ToHttpResponse(this);
    // }
}