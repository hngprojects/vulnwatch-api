using Application.Features.Profile.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using Domain.Enums;
using MediatR;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Identity;

namespace Application.Features.Profile;

public record UpdateProfileCommand(string? FirstName, string? LastName)
    : IRequest<Result<UserProfileDto>>;

public class UpdateProfileHandler(
    UserManager<User> userManager,
    INotificationPreferencesRepository notifPrefs,
    ICurrentUser currentUser)
    : IRequestHandler<UpdateProfileCommand, Result<UserProfileDto>>
{
    public async Task<Result<UserProfileDto>> Handle(UpdateProfileCommand cmd, CancellationToken ct)
    {
        var user = await userManager.FindByIdAsync(currentUser.UserId.ToString());

        if (user is null)
            return Result<UserProfileDto>.Failure(Error.NotFound("User not found."));

        user.UpdateProfile(cmd.FirstName, cmd.LastName);

        var result = await userManager.UpdateAsync(user);

        if (!result.Succeeded)
        {
            var error = result.Errors.First().Description;
            return Result<UserProfileDto>.Failure(Error.Validation(error));
        }

        var prefs = await notifPrefs.GetByUserId(currentUser.UserId, ct);

        var prefsDto = prefs is null ? null : new NotificationPreferencesDto(
            EmailAlerts: prefs.EmailAlerts,
            SlackAlerts: prefs.SlackAlerts,
            PushNotifications: prefs.PushNotifications
        );

        return Result<UserProfileDto>.Success(new UserProfileDto(
            Id:              user.Id,
            Email:           user.Email!,
            FirstName:       user.FirstName,
            LastName:        user.LastName,
            ProfilePictureUrl: user.ProfilePictureUrl,
            EmailConfirmed:  user.EmailConfirmed,
            HasGoogleLinked: !string.IsNullOrWhiteSpace(user.GoogleId),
            NotificationPreferences: prefsDto,
            CreatedAt:       user.CreatedAt,
            UpdatedAt:       user.UpdatedAt
        ));
    }
}