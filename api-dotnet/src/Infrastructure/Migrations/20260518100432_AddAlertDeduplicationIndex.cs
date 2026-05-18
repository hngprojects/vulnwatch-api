using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddAlertDeduplicationIndex : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<int>(
                name: "SurfaceTypes",
                table: "Scans",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<string>(
                name: "DeduplicationKey",
                table: "Alerts",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.CreateIndex(
                name: "IX_Alerts_Deduplication",
                table: "Alerts",
                columns: new[] { "UserId", "Type", "DomainId", "DeduplicationKey" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_Alerts_Deduplication",
                table: "Alerts");

            migrationBuilder.DropColumn(
                name: "SurfaceTypes",
                table: "Scans");

            migrationBuilder.DropColumn(
                name: "DeduplicationKey",
                table: "Alerts");
        }
    }
}
