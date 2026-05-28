// src/Application/Features/Integrations/HandleSlackCallback.cs
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using Domain.Enums;
using MediatR;
using Microsoft.AspNetCore.Identity;

namespace Application.Features.Integrations;

public record HandleSlackCallbackCommand(
    string Code,
    string State) : IRequest<Result<SlackCallbackResponse>>;

public record SlackCallbackResponse(string TeamName, string TeamId);

public class HandleSlackCallbackHandler(
    IRedisService stateStore,
    ISlackOAuthService slackOAuth,
    IIntegrationRepository integrations,
    UserManager<User> userManager)
    : IRequestHandler<HandleSlackCallbackCommand, Result<SlackCallbackResponse>>
{
    public async Task<Result<SlackCallbackResponse>> Handle(
        HandleSlackCallbackCommand cmd, CancellationToken ct)
    {
        // 1. Validate & consume state — prevents CSRF and replay
        var userId = await stateStore.ValidateSlackState(cmd.State, ct);
        if (userId is null || userId == Guid.Empty)
            return Result<SlackCallbackResponse>.Failure(
                Error.Validation("Invalid or expired state parameter."));

        // 1b. Verify user exists in the database
        var user = await userManager.FindByIdAsync(userId.Value.ToString());
        if (user is null)
            return Result<SlackCallbackResponse>.Failure(
                Error.Validation("User not found. Please try logging in again."));

        // 2. Exchange code for token
        var token = await slackOAuth.ExchangeCodeAsync(cmd.Code, ct);
        if (!token.Ok || string.IsNullOrWhiteSpace(token.AccessToken))
            return Result<SlackCallbackResponse>.Failure(
                Error.Validation($"Slack token exchange failed: {token.Error ?? "unknown error"}"));

        // 3. Upsert integration — one row per user per provider
        var existing = await integrations.GetByUserAndProvider(
            userId.Value, IntegrationProvider.Slack, ct);

        if (existing is not null)
        {
            // Re-activating or updating workspace connection
            existing.Activate();

            // InstallationId holds the team ID — use reflection only if
            // you don't want to add a domain method. Adding a method is cleaner:
            existing.UpdateInstallation(token.TeamId!);
        }
        else
        {
            var integration = Integration.Create(
                userId.Value,
                provider: IntegrationProvider.Slack,
                installationId: token.TeamId!);

            integration.Activate();
            await integrations.AddAsync(integration, ct);
        }

        await integrations.SaveChangesAsync(ct);

        return Result<SlackCallbackResponse>.Success(
            new SlackCallbackResponse(
                token.TeamName ?? "Your workspace",
                token.TeamId!));
    }
}