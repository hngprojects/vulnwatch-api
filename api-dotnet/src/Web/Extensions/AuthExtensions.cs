using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Authorization;
using Microsoft.IdentityModel.Tokens;
using System.Text;
using Application.Helpers;
using Application.Interfaces;
using Infrastructure.Services;
using Domain.Common;
using Microsoft.AspNetCore.Authorization.Policy;
using System.Net;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Web.Extensions;

public class AuthorizationResultHandler : IAuthorizationMiddlewareResultHandler
{
    private readonly AuthorizationMiddlewareResultHandler _defaultHandler = new();

    public async Task HandleAsync(
        RequestDelegate next,
        HttpContext context,
        AuthorizationPolicy policy,
        PolicyAuthorizationResult authorizeResult)
    {
        // Let successful requests through immediately
        if (authorizeResult.Succeeded)
        {
            await next(context);
            return;
        }

        // Pick the right error based on whether the user is authenticated at all
        var isAuthenticated = context.User.Identity?.IsAuthenticated ?? false;

        Error error;
        HttpStatusCode statusCode;

        if (!isAuthenticated)
        {
            error = context.Items.TryGetValue("AuthError", out var stored) && stored is Error authErr
                         ? authErr
                         : Error.Unauthorized("No valid token.");
            statusCode = HttpStatusCode.Unauthorized;
        }
        else
        {
            error = Error.Forbidden("Insufficient permission.");
            statusCode = HttpStatusCode.Forbidden;
        }

        await WriteErrorResponseAsync(context, error, statusCode);
    }

    private static async Task WriteErrorResponseAsync(HttpContext context, Error error, HttpStatusCode statusCode)
    {
        context.Response.StatusCode = (int)statusCode;
        context.Response.ContentType = "application/json";

        var result = Result<object>.Failure(error);

        var body = JsonSerializer.Serialize(result, new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            Converters = { new JsonStringEnumConverter() }
        });

        await context.Response.WriteAsync(body);
    }
}
