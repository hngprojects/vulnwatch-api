using Application.Features.Auth;
using Application.Features.Auth.DTOs;
using Application.Features.Integrations;
using Application.Features.Integrations.DTOs;
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
public class IntegrationsController(IMediator mediator) : ControllerBase
{
    /// <summary>
    /// Handles Slack OAuth callback and connects a Slack workspace to the user account.
    /// </summary>
    /// <param name="code">OAuth authorization code returned by Slack.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <response code="200">Slack successfully connected.</response>
    /// <response code="400">Invalid or expired authorization code.</response>
    /// <response code="401">User is not authenticated.</response>
    [HttpGet("slack")]
    public async Task<ActionResult<Result<MessageResponse>>> SlackCallback(
        [FromQuery] string code,
        CancellationToken ct)
    {
        var result = await mediator.Send(new ConnectSlackCommand(code), ct);
        return result.ToHttpResponse(this);
    }

    /// <summary>
    /// Disconnects the currently connected Slack workspace from the user account.
    /// </summary>
    /// <param name="ct">Cancellation token.</param>
    /// <response code="200">Slack successfully disconnected.</response>
    /// <response code="400">No active Slack connection found.</response>
    /// <response code="401">User is not authenticated.</response>
    [HttpDelete("slack")]
    public async Task<ActionResult<Result<MessageResponse>>> DisconnectSlack(
        CancellationToken ct)
    {
        var result = await mediator.Send(new DisconnectSlackCommand(), ct);
        return result.ToHttpResponse(this);
    }

    /// <summary>
    /// Retrieves the current Slack integration status for the authenticated user.
    /// </summary>
    /// <param name="ct">Cancellation token.</param>
    /// <response code="200">Returns Slack connection status.</response>
    /// <response code="401">User is not authenticated.</response>
    [HttpGet("slack/status")]
    public async Task<ActionResult<Result<SlackStatusSummary>>> SlackStatus(
        CancellationToken ct)
    {
        var result = await mediator.Send(new GetSlackStatusQuery(), ct);
        return result.ToHttpResponse(this);
    }
}