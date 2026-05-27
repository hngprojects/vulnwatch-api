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

    [HttpGet("slack")]
    public async Task<ActionResult<Result<MessageResponse>>> SlackCallback(
        [FromQuery] string code,
        CancellationToken ct)
    {
        var result = await mediator.Send(new ConnectSlackCommand(code), ct);
        return result.ToHttpResponse(this);
    }

    [HttpDelete("slack")]
    public async Task<ActionResult<Result<MessageResponse>>> DisconnectSlack(
        CancellationToken ct)
    {
        var result = await mediator.Send(new DisconnectSlackCommand(), ct);
        return result.ToHttpResponse(this);
    }

    [HttpGet("slack/status")]
    public async Task<ActionResult<Result<SlackStatusSummary>>> SlackStatus(
        CancellationToken ct)
    {
        var result = await mediator.Send(new GetSlackStatusQuery(), ct);
        return result.ToHttpResponse(this);
    }
}