using Application.Features.Scans;
using Application.Features.Scans.DTOs;
using Domain.Common;
using MediatR;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Authorization;
using Web.Extensions;
using Microsoft.AspNetCore.RateLimiting;



namespace Web.Controllers;

/**
 * ScansController: Handles all HTTP requests related to vulnerability scans.
 * Intern-friendly: This is the entry point for the API.
 */
[EnableRateLimiting(RateLimitExtensions.GeneralPolicy)]
[Authorize]
[ApiController]
[Route("api/[controller]")]
public class ScansController : ControllerBase
{
    private readonly IMediator _mediator;

    public ScansController(IMediator mediator)
    {
        _mediator = mediator;
    }

    /// <summary>
    /// Starts a vulnerability scan for a domain.
    /// </summary>
    /// <param name="idempotencyKey">A unique key to prevent duplicate scan requests.</param>
    /// <param name="body">Scan request payload.</param>
    /// <returns>The created scan job.</returns>
    [HttpPost]
    public async Task<ActionResult<Result<StartScanResponse>>> InitiateScan(
        [FromHeader(Name = "Idempotency-Key")] Guid idempotencyKey,
        [FromBody] StartScanRequest body)
    {
        var command = new StartScanCommand(body.Domain, body.Coverage, body.SurfaceTypes, idempotencyKey);
        var result = await _mediator.Send(command);
        return result.ToHttpResponse(this);
    }

    [HttpGet("{domainId:guid}/history")]
    public async Task<ActionResult<Result<PagedResult<ScanSummary>>>> GetScanHistory(Guid domainId, [FromQuery] GetScanHistoryRequest request, CancellationToken ct)
    {
        var query = new GetScanHistoryQuery(domainId, request.Status, request.Coverage,
                                        request.SortBy, request.Order, request.Page, request.PageSize);

        var result = await _mediator.Send(query, ct);
        return result.ToHttpResponse(this);

    }

    [HttpGet("{scanId:guid}/report")]
    public async Task<ActionResult<Result<ScanReportDto>>> GetReport(Guid scanId, CancellationToken ct)
    {
        var result = await _mediator.Send(new GetScanReportQuery(scanId), ct);
        return result.ToHttpResponse(this);
    }
}
