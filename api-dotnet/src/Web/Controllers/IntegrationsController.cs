// src/Web/Controllers/SlackController.cs
using Application.Features.Integrations;
using Domain.Common;
using MediatR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Web.Extensions;

namespace Web.Controllers;

[EnableRateLimiting(RateLimitExtensions.GeneralPolicy)]
[AllowAnonymous]               // Slack calls callback with no JWT
[ApiController]
[Route("api/[controller]")]
public class IntegrationsController(IMediator mediator, IConfiguration config)
    : ControllerBase
{
    /// <summary>
    /// Initiates the Slack OAuth flow. Must be called by an authenticated user.
    /// Redirects the browser to Slack's authorization page.
    /// </summary>
    [Authorize]                // Only this endpoint requires a JWT
    [HttpGet("slack/authorize")]
    public async Task<ActionResult<Result<SlackAuthUrlResponse>>> Connect(CancellationToken ct)
    {
        var result = await mediator.Send(new InitiateSlackOAuthCommand(), ct);

        if (!result.IsSuccess)
            return StatusCode(
                StatusCodes.Status500InternalServerError,
                result);

        return result.ToHttpResponse(this);
    }

    /// <summary>
    /// Slack redirects here after the user approves or denies the OAuth request.
    /// No JWT required — the state parameter is the only auth mechanism here.
    /// </summary>
    [HttpGet("slack/callback")]
    public async Task<ActionResult<Result<SlackCallbackResponse>>> Callback(
        [FromQuery] string? code,
        [FromQuery] string? state,
        [FromQuery] string? error,
        CancellationToken ct)
    {
        var frontendBase = config["FrontendUrl:SlackCallback"]
                        ?? config["FrontendUrl:Verify"]!
                            .Replace("/verify", "");

        if (!string.IsNullOrWhiteSpace(error))
            return Redirect(
                $"{frontendBase}/settings/integrations?slack=denied&reason={error}");

        if (string.IsNullOrWhiteSpace(code) || string.IsNullOrWhiteSpace(state))
            return Redirect(
                $"{frontendBase}/settings/integrations?slack=error&reason=missing_params");

        var result = await mediator.Send(
            new HandleSlackCallbackCommand(code, state), ct);

        if (!result.IsSuccess)
            return Redirect(
                $"{frontendBase}/settings/integrations?slack=error" +
                $"&reason={Uri.EscapeDataString(result.Error!.Message)}");

        return Redirect(
            $"{frontendBase}/settings/integrations?slack=success" +
            $"&team={Uri.EscapeDataString(result.Value!.TeamName)}");
    }
}

// [EnableRateLimiting(RateLimitExtensions.GeneralPolicy)]
// [Authorize]
// [ApiController]
// [Route("api/[controller]")]
// public class IntegrationsController(IMediator mediator, IConfiguration config)
//     : ControllerBase
// {
//     /// <summary>
//     /// Returns the Slack authorization URL. Redirect the user's browser to it.
//     /// </summary>
//     [HttpGet("slack/authorize")]
//     public async Task<ActionResult<Result<SlackAuthUrlResponse>>> Connect(
//         CancellationToken ct)
//     {
//         var result = await mediator.Send(
//             new InitiateSlackOAuthCommand(), ct);

//         return result.ToHttpResponse(this);
//     }

//     /// <summary>
//     /// Slack redirects here after the user approves (or denies) the OAuth request.
//     /// This endpoint is called by Slack's servers — NOT by your frontend directly.
//     /// </summary>
//     [AllowAnonymous]          // Slack can't send a JWT — state param is the auth
//     [HttpGet("slack/callback")]
//     public async Task<IActionResult> Callback(
//         [FromQuery] string? code,
//         [FromQuery] string? state,
//         [FromQuery] string? error,
//         CancellationToken ct)
//     {
//         var frontendBase = config["FrontendUrl:SlackCallback"]
//                         ?? config["FrontendUrl:Verify"]!
//                             .Replace("/verify", "");

//         // User denied the request
//         if (!string.IsNullOrWhiteSpace(error))
//             return Redirect($"{frontendBase}/settings/integrations?slack=denied&reason={error}");

//         if (string.IsNullOrWhiteSpace(code) || string.IsNullOrWhiteSpace(state))
//             return Redirect($"{frontendBase}/settings/integrations?slack=error&reason=missing_params");

//         var result = await mediator.Send(
//             new HandleSlackCallbackCommand(code, state), ct);

//         if (!result.IsSuccess)
//             return Redirect(
//                 $"{frontendBase}/settings/integrations?slack=error" +
//                 $"&reason={Uri.EscapeDataString(result.Error!.Message)}");

//         return Redirect(
//             $"{frontendBase}/settings/integrations?slack=success" +
//             $"&team={Uri.EscapeDataString(result.Value!.TeamName)}");
//     }
// }