using Application.Features.Scans;
using Domain.Common;
using MediatR;
using Microsoft.AspNetCore.Mvc;
using Web.Extensions;

namespace Web.Controllers;

/**
 * ScansController: Handles all HTTP requests related to vulnerability scans.
 * Intern-friendly: This is the entry point for the API.
 */
[ApiController]
[Route("api/[controller]")]
public class ScansController : ControllerBase
{
    private readonly IMediator _mediator;

    public ScansController(IMediator mediator)
    {
        _mediator = mediator;
    }

    [HttpPost]
    public async Task<ActionResult<Result<ScanResponse>>> Create(
        [FromHeader(Name = "Idempotency-Key")] Guid idempotencyKey,
        [FromBody] CreateScanRequest body)
    {
        var command = new CreateScanCommand(idempotencyKey, body.Domain, body.ScanTypes);
        var result = await _mediator.Send(command);
        return result.ToHttpResponse(this);
    }
}
