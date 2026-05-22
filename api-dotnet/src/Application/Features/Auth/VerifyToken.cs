using System.Net;
using Application.Features.Auth.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using MediatR;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace Application.Features.Auth;

public record VerifyTokenCommand(string UserId, string Token) : IRequest<Result<MessageResponse>>;

public class VerifyTokenHandler : IRequestHandler<VerifyTokenCommand, Result<MessageResponse>>
{
    private readonly UserManager<User> _userManager;
    private readonly INotificationPreferencesRepository _notifPrefs;

    public VerifyTokenHandler(
        UserManager<User> userManager,
        INotificationPreferencesRepository notifPrefs)
    {
        _userManager = userManager;
        _notifPrefs = notifPrefs;
    }

    public async Task<Result<MessageResponse>> Handle(
    VerifyTokenCommand cmd, CancellationToken ct)
    {
        var user = await _userManager.FindByIdAsync(cmd.UserId);
        if (user is null)
            return Result<MessageResponse>.Failure(Error.NotFound("User not found."));

        if (!user.EmailConfirmed)
        {
            var decodedToken = WebUtility.UrlDecode(cmd.Token);
            var identityResult = await _userManager.ConfirmEmailAsync(user, decodedToken);

            if (!identityResult.Succeeded)
                return Result<MessageResponse>.Failure(
                    Error.Validation(identityResult.Errors.First().Description));

            user.Activate();
            await _userManager.UpdateAsync(user);
        }

        // Always attempt prefs seeding — idempotent, duplicate-safe
        await TryCreateDefaultPrefsAsync(user.Id, ct);

        return Result<MessageResponse>.Success(
            MessageResponse.Create("Email verified! Proceed to login."));
    }

    private async Task TryCreateDefaultPrefsAsync(Guid userId, CancellationToken ct)
    {
        try
        {
            var prefs = NotificationPreferences.Create(userId, emailAlerts: true);
            await _notifPrefs.AddAsync(prefs, ct);
            await _notifPrefs.SaveChangesAsync(ct);
        }
        catch (DbUpdateException ex)
            when (ex.InnerException?.GetType().FullName == "Npgsql.PostgresException" &&
                  ex.InnerException.GetType().GetProperty("SqlState")?.GetValue(ex.InnerException) as string == "23505")
        {
            // Prefs already exist — another path seeded them first. No-op.
        }
    }
}