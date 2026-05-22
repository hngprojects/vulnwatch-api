using Application.Features.Scans;
using Application.Features.Scans.DTOs;
using Domain.Common;
using MediatR;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Authorization;
using Web.Extensions;
using Microsoft.AspNetCore.RateLimiting;
using Application.Features.Profile.DTOs;
using Application.Features.Profile;
using Application.Features.Auth.DTOs;

namespace Web.Controllers;

/**
 * ProfileController: Handles all HTTP requests related to user profiles.
 * Intern-friendly: This is the entry point for the API.
 */
[EnableRateLimiting(RateLimitExtensions.GeneralPolicy)]
[Authorize]
[ApiController]
[Route("api/[controller]")]
public class ProfileController : ControllerBase
{
    private readonly IMediator _mediator;

    public ProfileController(IMediator mediator)
    {
        _mediator = mediator;
    }

    [HttpGet]
    public async Task<ActionResult<Result<UserProfileDto>>> GetProfile(CancellationToken ct)
    {
        var result = await _mediator.Send(new GetProfileQuery(), ct);
        return result.ToHttpResponse(this);
    }

    [HttpPut]
    public async Task<ActionResult<Result<UserProfileDto>>> UpdateProfile([FromBody] UpdateProfileRequest request, CancellationToken ct)
    {
        var command = new UpdateProfileCommand(request.FirstName, request.LastName);
        var result = await _mediator.Send(command, ct);
        return result.ToHttpResponse(this);
    }

    [HttpDelete]
    public async Task<ActionResult<Result<MessageResponse>>> DeleteAccount(CancellationToken ct)
    {
        var result = await _mediator.Send(new DeleteAccountCommand(), ct);
        return result.ToHttpResponse(this);
        
    }
}