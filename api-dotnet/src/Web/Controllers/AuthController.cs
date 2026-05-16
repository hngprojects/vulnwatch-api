using Application.Features.Auth;
using Application.Features.Auth.DTOs;
using Domain.Common;
using MediatR;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Web.Extensions;

namespace Web.Controllers;

[EnableRateLimiting(RateLimitExtensions.AuthPolicy)]
[ApiController]
[Route("api/[controller]")]
public class AuthController : ControllerBase
{
    private readonly IMediator _mediator;

    public AuthController(IMediator mediator) => _mediator = mediator;

    [HttpPost("register")]
    public async Task<ActionResult<Result<MessageResponse>>> Register(RegisterRequest request)
    {
        var result = await _mediator.Send(new RegisterCommand(request.Email, request.Password, request.FirstName, request.LastName));
        return result.ToHttpResponse(this);
    }

    [HttpPost("login")]
    public async Task<ActionResult<Result<AuthResponse>>> Login(LoginRequest request)
    {
        var result = await _mediator.Send(new LoginCommand(request.Email, request.Password));
        return result.ToHttpResponse(this);
    }

    [HttpPost("resend")]
    public async Task<ActionResult<Result<MessageResponse>>> ResendVerification(ResendTokenRequest request)
    {
        var result = await _mediator.Send(new ResendVerificationCommand(request.Email));
        return result.ToHttpResponse(this);
    }

    [HttpPost("google")]
    public async Task<ActionResult<Result<AuthResponse>>> GoogleLogin(GoogleLoginRequest request)
    {
        var result = await _mediator.Send(new GoogleLoginCommand(request.IdToken));
        return result.ToHttpResponse(this);
    }

    [HttpGet("verify")]
    public async Task<ActionResult<Result<MessageResponse>>> VerifyToken(
        [FromQuery] string userId,
        [FromQuery] string token,
        CancellationToken ct)
    {
        var result = await _mediator.Send(new VerifyTokenCommand(userId, token), ct);
        return result.ToHttpResponse(this);
    }

    [HttpPost("forgot-password")]
    public async Task<ActionResult<Result<MessageResponse>>> ForgotPassword(ForgotPasswordRequest request)
    {
        var result = await _mediator.Send(new ForgotPasswordCommand(request.Email));
        return result.ToHttpResponse(this);
    }

    [HttpPost("reset-password")]
    public async Task<ActionResult<Result<MessageResponse>>> ResetPassword(ResetPasswordRequest request)
    {
        var result = await _mediator.Send(new ResetPasswordCommand(request.Email, request.Token, request.NewPassword));
        return result.ToHttpResponse(this);
    }
}
