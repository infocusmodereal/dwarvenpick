package com.dwarvenpick.app.rbac

import com.dwarvenpick.app.auth.AuthenticatedPrincipalResolver
import com.dwarvenpick.app.auth.ErrorResponse
import com.dwarvenpick.app.inspector.InspectedObjectNotFoundException
import com.dwarvenpick.app.inspector.InspectedObjectType
import com.dwarvenpick.app.inspector.ObjectInspectorObjectRef
import com.dwarvenpick.app.inspector.ObjectInspectorService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/datasources")
class ObjectInspectorController(
    private val rbacService: RbacService,
    private val objectInspectorService: ObjectInspectorService,
    private val authenticatedPrincipalResolver: AuthenticatedPrincipalResolver,
) {
    @GetMapping("/{datasourceId}/inspector")
    fun inspectObject(
        @PathVariable datasourceId: String,
        @RequestParam type: String,
        @RequestParam schema: String,
        @RequestParam name: String,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            if (!rbacService.canUserQuery(principal, datasourceId)) {
                throw QueryAccessDeniedException("Datasource access denied for object inspector.")
            }

            val policy = rbacService.resolveQueryAccessPolicy(principal, datasourceId)
            val parsedType =
                runCatching { InspectedObjectType.valueOf(type.trim().uppercase()) }
                    .getOrElse { throw IllegalArgumentException("Unsupported object type '$type'.") }

            val response =
                objectInspectorService.inspect(
                    datasourceId = datasourceId,
                    credentialProfile = policy.credentialProfile,
                    objectRef =
                        ObjectInspectorObjectRef(
                            type = parsedType,
                            schema = schema,
                            name = name,
                        ),
                )
            ResponseEntity.ok(response)
        } catch (ex: DatasourceNotFoundException) {
            ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse(ex.message ?: "Datasource not found."))
        } catch (ex: InspectedObjectNotFoundException) {
            ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse(ex.message ?: "Object not found."))
        } catch (ex: QueryAccessDeniedException) {
            ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse(ex.message ?: "Datasource access denied."))
        } catch (ex: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ErrorResponse(ex.message ?: "Bad request."))
        } catch (ex: Exception) {
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("Unable to inspect this object right now."))
        }
    }
}
