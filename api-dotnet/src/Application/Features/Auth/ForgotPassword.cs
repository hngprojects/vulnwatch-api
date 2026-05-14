using System.Net;
using Application.Features.Auth.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using MediatR;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;

namespace Application.Features.Auth;

public record ForgotPasswordCommand(string Email) : IRequest<Result<MessageResponse>>;

public class ForgotPasswordHandler : IRequestHandler<ForgotPasswordCommand, Result<MessageResponse>>
{
    private readonly UserManager<User> _userManager;
    private readonly IEmailService _email;
    private readonly IConfiguration _config;
    private readonly ILogger<ForgotPasswordHandler> _logger;


    public ForgotPasswordHandler(UserManager<User> userManager, IEmailService email, IConfiguration config, ILogger<ForgotPasswordHandler> logger)
    {
        _userManager = userManager;
        _email = email;
        _config = config;
        _logger = logger;
    }

    public async Task<Result<MessageResponse>> Handle(ForgotPasswordCommand cmd, CancellationToken ct)
    {
        // Always return the same message so we don't reveal whether the email exists
        const string message = "If this email is registered, a reset link has been sent.";

        var user = await _userManager.FindByEmailAsync(cmd.Email);
        if (user is null)
            return Result<MessageResponse>.Success(MessageResponse.Create(message));

        var token = await _userManager.GeneratePasswordResetTokenAsync(user);
        var encodedToken = WebUtility.UrlEncode(token);

        var frontendUrl = _config["FrontendUrl:ForgotPassword"];
        var resetLink = $"{frontendUrl}/api/auth/reset-password?email={Uri.EscapeDataString(cmd.Email)}&token={encodedToken}";

        _logger.LogInformation("VERIFICATION LINK: {link}", resetLink);

        await _email.SendAsync(cmd.Email, "Reset your password", $"Click here to reset your password: {resetLink}");

        return Result<MessageResponse>.Success(MessageResponse.Create(message));
    }
}
