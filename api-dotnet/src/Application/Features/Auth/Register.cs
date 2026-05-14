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

public record RegisterCommand(string Email, string Password, string? FirstName = null, string? LastName = null) : IRequest<Result<MessageResponse>>;

public class RegisterHandler : IRequestHandler<RegisterCommand, Result<MessageResponse>>
{
    private readonly UserManager<User> _userManager;
    private readonly IJwtService _jwt;
    private readonly IEmailService _email;
    private readonly IConfiguration _config;

    private readonly ILogger<RegisterHandler> _logger;


    public RegisterHandler(UserManager<User> userManager, IJwtService jwt, IEmailService email, IConfiguration config, ILogger<RegisterHandler> logger)
    {
        _userManager = userManager;
        _jwt = jwt;
        _email = email;
        _config = config;
        _logger = logger;
    }

    public async Task<Result<MessageResponse>> Handle(RegisterCommand cmd, CancellationToken ct)
    {
        var existing = await _userManager.FindByEmailAsync(cmd.Email);
        if (existing is not null)
            return Result<MessageResponse>.Failure(Error.Conflict("Email is already registered."));

        var user = User.Create(cmd.Email, cmd.FirstName, cmd.LastName);
        var result = await _userManager.CreateAsync(user, cmd.Password);

        if (!result.Succeeded)
            return Result<MessageResponse>.Failure(Error.Validation(result.Errors.First().Description));

        var verificationToken = await _userManager.GenerateEmailConfirmationTokenAsync(user);

        var encodedToken = WebUtility.UrlEncode(verificationToken);

        var verificationLink = $"{_config["FrontendUrl:Verify"]}/?userId={user.Id}&token={encodedToken}";

        _logger.LogInformation("VERIFICATION LINK: {link}", verificationLink);

        var displayName = string.IsNullOrWhiteSpace(user.FirstName)
            ? user.Email!
            : user.FirstName;

        var body = BuildVerificationEmailBody(displayName, verificationLink);

        await _email.SendAsync(user.Email!, "Verify Your Email", body);

        return Result<MessageResponse>.Success(MessageResponse.Create("Registration successful. Verification link has been sent to your email."));
    }

    private string BuildVerificationEmailBody(string userName, string verificationLink)
    {
        return $@"
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset='UTF-8'>
        <title>Verify your email</title>
    </head>
    <body style='font-family: Arial, sans-serif; background-color: #f9f9f9; padding: 20px;'>
        <div style='max-width: 600px; margin: auto; background: #ffffff; padding: 30px; border-radius: 8px;'>
            
            <h2 style='color: #333;'>Welcome, {userName} 👋</h2>

            <p style='font-size: 16px; color: #555;'>
                Thanks for signing up. Please confirm your email address to activate your account.
            </p>

            <p style='font-size: 16px; color: #555;'>
                Click the button below to verify your email:
            </p>

            <div style='text-align: center; margin: 30px 0;'>
                <a href='{verificationLink}' 
                style='background-color: #4CAF50; color: white; padding: 12px 24px; 
                        text-decoration: none; border-radius: 5px; display: inline-block;'>
                    Verify Email
                </a>
            </div>

            <p style='font-size: 14px; color: #777;'>
                If the button doesn’t work, copy and paste this link into your browser:
            </p>

            <p style='font-size: 12px; color: #999; word-break: break-all;'>
                {verificationLink}
            </p>

            <hr style='margin-top: 30px;' />

            <p style='font-size: 12px; color: #aaa;'>
                If you didn’t create this account, you can safely ignore this email.
            </p>

        </div>
    </body>
    </html>
    ";
    }
}
