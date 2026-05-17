using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddPerformanceIndexes : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_Scans_DomainId",
                table: "Scans");

            migrationBuilder.DropIndex(
                name: "IX_Scans_UserId",
                table: "Scans");

            migrationBuilder.DropIndex(
                name: "IX_Domains_UserId",
                table: "Domains");

            migrationBuilder.AddColumn<int>(
                name: "Coverage",
                table: "Scans",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.CreateIndex(
                name: "IX_Scans_DomainId_Active",
                table: "Scans",
                column: "DomainId",
                filter: "\"Status\" IN ('Queued', 'Running')");

            migrationBuilder.CreateIndex(
                name: "IX_Scans_DomainId_Status",
                table: "Scans",
                columns: new[] { "DomainId", "Status" });

            migrationBuilder.CreateIndex(
                name: "IX_Scans_UserId_Status",
                table: "Scans",
                columns: new[] { "UserId", "Status" });

            migrationBuilder.CreateIndex(
                name: "IX_Domains_DomainName_VerificationStatus",
                table: "Domains",
                columns: new[] { "DomainName", "VerificationStatus" });

            migrationBuilder.CreateIndex(
                name: "IX_Domains_UserId_DomainName",
                table: "Domains",
                columns: new[] { "UserId", "DomainName" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Domains_UserId_VerificationStatus",
                table: "Domains",
                columns: new[] { "UserId", "VerificationStatus" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_Scans_DomainId_Active",
                table: "Scans");

            migrationBuilder.DropIndex(
                name: "IX_Scans_DomainId_Status",
                table: "Scans");

            migrationBuilder.DropIndex(
                name: "IX_Scans_UserId_Status",
                table: "Scans");

            migrationBuilder.DropIndex(
                name: "IX_Domains_DomainName_VerificationStatus",
                table: "Domains");

            migrationBuilder.DropIndex(
                name: "IX_Domains_UserId_DomainName",
                table: "Domains");

            migrationBuilder.DropIndex(
                name: "IX_Domains_UserId_VerificationStatus",
                table: "Domains");

            migrationBuilder.DropColumn(
                name: "Coverage",
                table: "Scans");

            migrationBuilder.CreateIndex(
                name: "IX_Scans_DomainId",
                table: "Scans",
                column: "DomainId");

            migrationBuilder.CreateIndex(
                name: "IX_Scans_UserId",
                table: "Scans",
                column: "UserId");

            migrationBuilder.CreateIndex(
                name: "IX_Domains_UserId",
                table: "Domains",
                column: "UserId");
        }
    }
}
