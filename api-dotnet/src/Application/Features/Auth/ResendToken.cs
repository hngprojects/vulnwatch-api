using MediatR;
using Domain.Common;
using Domain.Entities;
using Application.Interfaces;
using Microsoft.Extensions.Configuration;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.Logging;
using Application.Features.Auth.DTOs;
using System.Net;

namespace Application.Features.Auth;

public record ResendVerificationCommand(string Email) : IRequest<Result<MessageResponse>>;

public class ResendVerificationHandler(
    UserManager<User> userManager,
    IEmailService email,
    IConfiguration config,
    ILogger<ResendVerificationHandler> logger)
    : IRequestHandler<ResendVerificationCommand, Result<MessageResponse>>
{
    private const int CooldownMinutes = 2;

    public async Task<Result<MessageResponse>> Handle(ResendVerificationCommand cmd, CancellationToken ct)
    {
        const string message = "If this email is registered, a reset link has been sent.";

        var user = await userManager.FindByEmailAsync(cmd.Email);

        if (user is null)
            return Result<MessageResponse>.Success(MessageResponse.Create(message));

        if (user.EmailConfirmed)
            return Result<MessageResponse>.Failure(Error.Conflict("Email is already verified."));

        var verificationToken = await userManager.GenerateEmailConfirmationTokenAsync(user);

        var encodedToken = WebUtility.UrlEncode(verificationToken);
        var verificationLink = $"{config["FrontendUrl:Verify"]!}/?userId={user.Id}&token={encodedToken}";

        logger.LogInformation("RESEND VERIFICATION LINK: {link}", verificationLink);

        await userManager.UpdateAsync(user);

        var body = BuildVerificationEmailBody(user.UserName!, verificationLink);
        await email.SendAsync(user.Email!, "Verify Your Email", body);

        return Result<MessageResponse>.Success(MessageResponse.Create(message));

    }

    private static string BuildVerificationEmailBody(string userName, string verificationLink) => $@"
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset='UTF-8'>
            <title>Verify your email</title>
        </head>
        <body style='font-family: Arial, sans-serif; background-color: #f9f9f9; padding: 20px;'>
            <div style='max-width: 600px; margin: auto; background: #ffffff; padding: 30px; border-radius: 8px;'>
                <h2 style='color: #333;'>Hey {userName} 👋</h2>
                <p style='font-size: 16px; color: #555;'>
                    Here's your new verification link. This was requested because your previous link may have expired or didn't arrive.
                </p>
                <div style='text-align: center; margin: 30px 0;'>
                    <a href='{verificationLink}'
                    style='background-color: #4CAF50; color: white; padding: 12px 24px;
                            text-decoration: none; border-radius: 5px; display: inline-block;'>
                        Verify Email
                    </a>
                </div>
                <p style='font-size: 14px; color: #777;'>
                    If the button doesn't work, copy and paste this link into your browser:
                </p>
                <p style='font-size: 12px; color: #999; word-break: break-all;'>
                    {verificationLink}
                </p>
                <hr style='margin-top: 30px;' />
                <p style='font-size: 12px; color: #aaa;'>
                    If you didn't request this, you can safely ignore this email.
                </p>
            </div>
        </body>
        </html>";
}