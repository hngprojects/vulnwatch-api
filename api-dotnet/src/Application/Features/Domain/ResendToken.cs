using Application.Features.Domain.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Enums;
using MediatR;

namespace Application.Features.Domain;

public record ResendDomainTokenCommand(string Domain) : IRequest<Result<RegisterDomainResponse>>;

public class ResendDomainTokenHandler : IRequestHandler<ResendDomainTokenCommand, Result<RegisterDomainResponse>>
{
    private readonly IDomainRepository _domains;
    private readonly ICurrentUser _currentUser;
    private readonly ITokenService _token;

    public ResendDomainTokenHandler(IDomainRepository domains, ICurrentUser currentUser, ITokenService token)
    {
        _domains = domains;
        _currentUser = currentUser;
        _token = token;
    }

    public async Task<Result<RegisterDomainResponse>> Handle(ResendDomainTokenCommand cmd, CancellationToken ct)
    {
        var domain = cmd.Domain.ToLowerInvariant();

        var record = await _domains.GetByNameAndUser(domain, _currentUser.UserId, ct);

        if (record is null)
            return Result<RegisterDomainResponse>.Failure(Error.NotFound("Domain not registered"));

        if (record.VerificationStatus == VerificationStatus.Verified)
            return Result<RegisterDomainResponse>.Failure(Error.Validation("Domain is already verified"));

        var (rawToken, tokenHash) = _token.Generate();

        record.RegenerateToken(tokenHash);

        await _domains.SaveChangesAsync(ct);

        return Result<RegisterDomainResponse>.Success(RegisterDomainResponse.Create(rawToken, record));
    }
}