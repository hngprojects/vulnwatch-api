using Application.Features.Auth;
using Application.Features.Auth.DTOs;
using Application.Features.Domain;
using Application.Features.Domain.DTOs;
using Domain.Common;
using MediatR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Web.Extensions;

namespace Web.Controllers;
[EnableRateLimiting(RateLimitExtensions.GeneralPolicy)]

[ApiController]
[Route("api/[controller]")]
public class DomainsController : ControllerBase
{
    private readonly IMediator _mediator;

    public DomainsController(IMediator mediator) => _mediator = mediator;

    [HttpPost]
    [Authorize]
    public async Task<ActionResult<Result<RegisterDomainResponse>>> Register(RegisterDomainRequest request)
    {
        var result = await _mediator.Send(new RegisterDomainCommand(request.Domain));
        return result.ToHttpResponse(this);
    }

    [Authorize]
    [HttpPost("ResendToken")]
    public async Task<ActionResult<Result<RegisterDomainResponse>>> ResendToken(
        RegisterDomainRequest request,
        CancellationToken ct)
    {
        var result = await _mediator.Send(new ResendDomainTokenCommand(request.Domain), ct);
        return result.ToHttpResponse(this);
    }


    [Authorize]
    [HttpPut("{id:guid}/Verify")]
    public async Task<ActionResult<Result<VerifyDomainResponse>>> Verify(
        Guid id,
        CancellationToken ct)
    {
        var result = await _mediator.Send(new VerifyDomainCommand(id), ct);
        return result.ToHttpResponse(this);
    }

    [HttpGet]
    [Authorize]
    public async Task<ActionResult<Result<PagedResult<DomainSummary>>>> GetUserDomains([FromQuery] GetDomainsRequest request, CancellationToken ct)
    {

        var query = new GetDomainsQuery(request.Search, request.Status,
                                        request.SortBy, request.Order, request.Page, request.PageSize);

        var result = await _mediator.Send(query, ct);
        return result.ToHttpResponse(this);

    }

    [HttpGet("{domainId:guid}")]
    [Authorize]
    public async Task<ActionResult<Result<DomainSummary>>> GetSingleDomain(Guid domainId, CancellationToken ct)
    {
        var result = await _mediator.Send(new GetDomainByIdQuery(domainId), ct);
        return result.ToHttpResponse(this);
    }

    [HttpDelete("{domainId:guid}")]
    [Authorize]
    public async Task<ActionResult<Result<MessageResponse>>> DeleteDomain(Guid domainId, CancellationToken ct)
    {
        var result = await _mediator.Send(new DeleteDomainCommand(domainId), ct);
        return result.ToHttpResponse(this);
    }

}