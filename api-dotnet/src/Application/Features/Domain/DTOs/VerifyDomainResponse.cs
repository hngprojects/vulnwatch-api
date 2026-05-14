using Domain.Entities;
using Domain.Enums;

namespace Application.Features.Domain.DTOs;

public record VerifyDomainResponse(VerificationStatus Status, string? Message = null)
{
    public static VerifyDomainResponse Create(VerificationStatus status, string? message = null) => new(
        status,
        message);
}

